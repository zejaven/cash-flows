"""Конвертер выписки BNZ (YouMoney) из PDF в JSON.

    python parsers/bnz.py "docs/BNZ/YouMoney_2026-03-12_1.pdf" -o out.json

Формат: альбомный A4, одна таблица Date | Particulars | Type | Withdrawals |
Deposits | Balance, слева поля метаданных (имя, номер счёта). extract_table
pdfplumber теряет колонки Date и Balance, поэтому строки восстанавливаются из
координат слов: колонки задаются X-позициями заголовка, строки кластеризуются
по Y (дата стоит на пару пикселей выше остальной строки).

Особенности против Alatau-парсера:
- у строк нет времени и reference — для стабильной дедупликации синтезируется
  reference "<номер выписки>:<номер строки>" (порядок строк в выписке
  неизменен, периоды выписок не пересекаются);
- сверка тройная: пострачный running balance против колонки Balance, итог
  против CLOSING BALANCE и opening + deposits - withdrawals == closing.

JSON-контракт общий для всех парсеров (docs/Alatau City Bank/
bank-statement-structure.md). Выход: stdout либо -o; exit 0 — успех,
1 — расхождение сверки, 2 — ошибка вызова.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path

import pdfplumber

MONTHS = {
    "JAN": 1, "FEB": 2, "MAR": 3, "APR": 4, "MAY": 5, "JUN": 6,
    "JUL": 7, "AUG": 8, "SEP": 9, "OCT": 10, "NOV": 11, "DEC": 12,
    "JANUARY": 1, "FEBRUARY": 2, "MARCH": 3, "APRIL": 4, "JUNE": 6,
    "JULY": 7, "AUGUST": 8, "SEPTEMBER": 9, "OCTOBER": 10,
    "NOVEMBER": 11, "DECEMBER": 12,
}

ACCOUNT_NUMBER = re.compile(r"\b(\d{2}-\d{4}-\d{7}-\d{3})\b")
PERIOD = re.compile(
    r"FOR THE PERIOD\s+(\d{1,2})\s+([A-Z]{3,})\s+TO\s+(\d{1,2})\s+([A-Z]{3,})\s+(\d{4})", re.S)
STATEMENT_NO = re.compile(r"STATEMENT NO\.\s*(\d+)")
OPENING = re.compile(r"OPENING BALANCE\s+([\d,]+\.\d\d)(\s*DR)?")
CLOSING = re.compile(r"CLOSING BALANCE\s+([\d,]+\.\d\d)(\s*DR)?")
DAY_TOKEN = re.compile(r"^\d{1,2}$")
AMOUNT_TOKEN = re.compile(r"^[\d,]+\.\d\d$")
SKIP_ROW_TOKENS = {"OPENING", "CLOSING", "CARRIED", "BROUGHT", "BALANCE"}

# Тип-коды BNZ -> operation JSON-контракта. "Withdrawals" у расходных AT-строк
# включает в импорте перевод на наличный счёт (как банкоматы Alatau).
TYPE_CODES = {
    "PS": "Payment",        # EFTPOS
    "BP": "Payment",        # bill payment
    "AP": "Payment",        # automatic payment
    "DC": "Replenishment",  # direct credit
    "IB": "Transfers",      # internet banking transfer
    "FT": "Transfers",
}

CURRENCY = "NZD"


def num(text: str) -> Decimal:
    return Decimal(text.replace(",", ""))


def money(value: Decimal) -> float:
    return float(value)


@dataclass
class Row:
    date: str                  # ISO
    particulars: str
    type_code: str
    withdrawal: Decimal | None
    deposit: Decimal | None
    balance: Decimal | None
    reference: str = ""

    def to_json(self) -> dict:
        expense = self.withdrawal or Decimal(0)
        income = self.deposit or Decimal(0)
        return {
            "date": self.date,
            "time": None,
            "operation": self.operation(),
            "description": self.particulars or self.type_code,
            "reference": self.reference,
            "auth_code": None,
            "exchange_rate": None,
            "amount": money(expense if expense else income),
            "currency": CURRENCY,
            "income": money(income),
            "expense": money(expense),
        }

    def operation(self) -> str:
        code = self.type_code
        if code == "AT":
            return "Withdrawals" if self.withdrawal else "Replenishment"
        if code == "ET":
            return "Payment" if self.withdrawal else "Replenishment"
        if code in TYPE_CODES:
            return TYPE_CODES[code]
        return "Payment" if self.withdrawal else "Replenishment"


@dataclass
class Statement:
    number: int
    account: str
    period_from: str
    period_to: str
    opening: Decimal
    closing: Decimal
    rows: list[Row] = field(default_factory=list)


def parse_amount_sign(value: str, dr_suffix: str | None) -> Decimal:
    amount = num(value)
    return -amount if dr_suffix else amount


def resolve_year(day: int, month: int, from_iso: str, to_iso: str) -> str:
    """Год берётся из периода; период может пересекать границу года."""
    from_year = int(from_iso[:4])
    to_year = int(to_iso[:4])
    for year in {from_year, to_year}:
        iso = f"{year:04d}-{month:02d}-{day:02d}"
        if from_iso <= iso <= to_iso:
            return iso
    return f"{to_year:04d}-{month:02d}-{day:02d}"


def extract_rows(page, from_iso: str, to_iso: str) -> list[Row]:
    words = page.extract_words()
    header = {w["text"]: w for w in words
              if w["text"] in ("Date", "Particulars", "Type", "Withdrawals", "Deposits", "Balance")}
    if len(header) < 6:
        return []

    date_x0 = header["Date"]["x0"] - 6
    part_x0 = header["Particulars"]["x0"] - 6
    type_x0 = header["Type"]["x0"] - 6
    amount_zone = header["Type"]["x1"] + 10
    # суммы выровнены вправо: колонку определяет правый край слова
    cut_wd_dep = (header["Withdrawals"]["x1"] + header["Deposits"]["x1"]) / 2
    cut_dep_bal = (header["Deposits"]["x1"] + header["Balance"]["x1"]) / 2
    header_top = header["Date"]["top"]

    # строки: кластеризация по Y с допуском (дата стоит чуть выше строки)
    clusters: list[list[dict]] = []
    for word in sorted((w for w in words if w["top"] > header_top + 4), key=lambda w: w["top"]):
        if word["x1"] < date_x0:
            continue  # левая колонка метаданных
        if clusters and word["top"] - clusters[-1][-1]["top"] <= 4:
            clusters[-1].append(word)
        else:
            clusters.append([word])

    rows: list[Row] = []
    for cluster in clusters:
        tokens = [w["text"] for w in cluster]
        if any(t.upper() == "CLOSING" for t in tokens):
            break  # дальше только процентные ставки и юридический текст
        if any(t.upper() in SKIP_ROW_TOKENS for t in tokens):
            continue
        day = month = None
        particulars: list[str] = []
        type_code = ""
        withdrawal = deposit = balance = None
        for word in sorted(cluster, key=lambda w: w["x0"]):
            text = word["text"]
            if word["x0"] < part_x0:
                if DAY_TOKEN.match(text):
                    day = int(text)
                elif text[:3].upper() in MONTHS:
                    month = MONTHS[text[:3].upper()]
            elif word["x0"] < type_x0:
                particulars.append(text)
            elif word["x0"] < amount_zone:
                type_code = text
            elif AMOUNT_TOKEN.match(text):
                if word["x1"] <= cut_wd_dep:
                    withdrawal = num(text)
                elif word["x1"] <= cut_dep_bal:
                    deposit = num(text)
                else:
                    balance = num(text)
        if day is None or month is None:
            # продолжение длинного описания предыдущей строки
            if rows and particulars:
                rows[-1].particulars = f"{rows[-1].particulars} {' '.join(particulars)}".strip()
            continue
        if withdrawal is None and deposit is None:
            continue
        rows.append(Row(
            date=resolve_year(day, month, from_iso, to_iso),
            particulars=" ".join(particulars),
            type_code=type_code,
            withdrawal=withdrawal,
            deposit=deposit,
            balance=balance,
        ))
    return rows


def parse_statement(path: Path) -> Statement:
    with pdfplumber.open(path) as pdf:
        full_text = "\n".join(page.extract_text() or "" for page in pdf.pages)
        # заголовок таблицы вклинивается в текстовый поток посреди строки
        # "FOR THE PERIOD ..." — вычищаем его перед разбором периода
        clean_text = "\n".join(line for line in full_text.splitlines()
                               if not line.startswith("Date Particulars"))

        number = STATEMENT_NO.search(full_text)
        period = PERIOD.search(clean_text)
        opening = OPENING.search(full_text)
        closing = CLOSING.search(full_text)
        account = ACCOUNT_NUMBER.search(full_text)
        missing = [name for name, match in [
            ("statement number", number), ("period", period),
            ("opening balance", opening), ("closing balance", closing),
            ("account number", account)] if match is None]
        if missing:
            raise ValueError("Not a BNZ YouMoney statement (missing: %s)" % ", ".join(missing))

        day1, month1, day2, month2, year2 = period.groups()
        to_iso = f"{int(year2):04d}-{MONTHS[month2]:02d}-{int(day2):02d}"
        from_year = int(year2) if MONTHS[month1] <= MONTHS[month2] else int(year2) - 1
        from_iso = f"{from_year:04d}-{MONTHS[month1]:02d}-{int(day1):02d}"

        statement = Statement(
            number=int(number.group(1)),
            account=account.group(1),
            period_from=from_iso,
            period_to=to_iso,
            opening=parse_amount_sign(*opening.groups()),
            closing=parse_amount_sign(*closing.groups()),
        )
        for page in pdf.pages:
            statement.rows.extend(extract_rows(page, from_iso, to_iso))

    # стабильный дедуп-ключ: у BNZ нет ни времени, ни reference, а два одинаковых
    # платежа в один день легальны — нумеруем строки в пределах выписки
    for index, row in enumerate(statement.rows, start=1):
        row.reference = f"{statement.number}:{index}"
    return statement


def verify(statement: Statement) -> list[str]:
    problems = []
    running = statement.opening
    for row in statement.rows:
        running += (row.deposit or Decimal(0)) - (row.withdrawal or Decimal(0))
        if row.balance is not None and running != row.balance:
            problems.append(
                f"MISMATCH: running balance {running} != statement balance {row.balance}"
                f" at {row.date} '{row.particulars}'")
            running = row.balance  # не каскадировать одну ошибку на все строки
    if running != statement.closing:
        problems.append(
            f"MISMATCH: computed closing {running} != CLOSING BALANCE {statement.closing}")
    return problems


def to_json(statement: Statement, source: str) -> dict:
    deposits = sum((r.deposit or Decimal(0)) for r in statement.rows)
    withdrawals = sum((r.withdrawal or Decimal(0)) for r in statement.rows)
    return {
        "source": source,
        "bank": {"name": "BNZ", "bic": None},
        "statement": {
            "type": f"YouMoney #{statement.number}",
            "generated_at": None,
            "period_from": statement.period_from,
            "period_to": statement.period_to,
        },
        "holder": {"name": None, "iin": None, "card_number": None},
        "accounts": [{
            "account": statement.account,
            "currency": CURRENCY,
            "summary": {
                "balance_start": money(statement.opening),
                "balance_end": money(statement.closing),
                "deposits": money(deposits),
                "withdrawals": money(withdrawals),
                "transfers": None,
                "purchases": None,
                "fees": None,
                "others": None,
                "amount_in_process": None,
                "balance_total": money(statement.closing),
            },
            "transactions": [row.to_json() for row in statement.rows],
        }],
    }


def redact(document: dict) -> dict:
    """Скрыть номер счёта (последние 3 цифры остаются для маппинга)."""
    for account in document["accounts"]:
        account["account"] = f"****{account['account'][-3:]}"
    return document


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Конвертировать PDF-выписку BNZ YouMoney в JSON.")
    parser.add_argument("pdf", type=Path, help="путь к PDF-выписке")
    parser.add_argument("-o", "--output", type=Path,
                        help="файл для записи (по умолчанию — stdout)")
    parser.add_argument("--redact", action="store_true",
                        help="скрыть номер счёта")
    parser.add_argument("--indent", type=int, default=2,
                        help="отступ в JSON (0 — компактно)")
    parser.add_argument("--no-verify", action="store_true",
                        help="не сверять контрольные суммы")
    args = parser.parse_args(argv)

    if not args.pdf.is_file():
        print(f"Файл не найден: {args.pdf}", file=sys.stderr)
        return 2

    try:
        statement = parse_statement(args.pdf)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2

    problems = [] if args.no_verify else verify(statement)
    document = to_json(statement, args.pdf.name)
    if args.redact:
        document = redact(document)

    payload = json.dumps(document, ensure_ascii=False, indent=args.indent or None)
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
        print(f"{len(statement.rows)} транзакций -> {args.output}", file=sys.stderr)
    else:
        print(payload)

    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    if not args.no_verify:
        print("Сверка с контрольными суммами выписки пройдена.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
