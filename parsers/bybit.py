"""Конвертер выгрузки ByBit (Asset History) из CSV/zip в JSON.

    python parsers/bybit.py "docs/ByBit/assetHistory_869355_20260121_20260721.zip" -o out.json

Формат: ByBit экспортирует архив с двумя CSV — карточные операции
(bybitCardTransactionHistory) и криптовалютные вводы/выводы
(withdrawDepositHistory). Парсер принимает исходный zip либо любой из CSV по
отдельности; формат определяется по содержимому (zipfile.is_zipfile /
заголовок), не по расширению. Первая строка каждого CSV — преамбула
"UID: ..., Company Name: , Country: ", настоящие заголовки во второй строке.

Семантика карточного CSV: реальное движение денег — только строки
Type=DEDUCT + Order Type="Transaction Completed" + Status=SUCCESS; Total Fee
считается отдельной комиссией и эмитится отдельной строкой Fee (reference
"<txid>:fee"). FREEZE/UNFREEZE — холды: в транзакции не попадают (FR-4.9);
незакрытые холды достоверно не вычисляются (Payment ID у FREEZE и парного
DEDUCT не совпадают), поэтому amount_in_process = null.
Transaction Amount/Currency — списание с карты (USD),
Payment Amount/Payment Currency — сумма мерчанта; при несовпадении валют
заполняется exchange_rate. Крипто-CSV: Deposit -> Replenishment,
Withdraw -> Transfers (валюта строки — актив, например USDT).

Особенности против PDF-парсеров: в выгрузке нет ни балансов, ни печатных
итогов, поэтому сверка структурная (см. parsers/CONTRACT.md): точное
совпадение заголовков, разбор каждой строки, только известные словари
Type/Order Type/Status, уникальные reference, неотрицательные суммы.
Summary-поля deposits/purchases/transfers/fees выведены из строк, балансы
null. Период — из имени файла (…_YYYYMMDD_YYYYMMDD…), иначе min/max дат.

JSON-контракт общий для всех парсеров (parsers/CONTRACT.md). Выход: stdout
либо -o; exit 0 — успех, 1 — расхождение сверки, 2 — ошибка вызова.
"""
from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
import zipfile
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path

CARD_HEADER = [
    "Uid", "Last 4 Digits", "Type", "Order Type", "Status", "Transaction Amount",
    "Total Fee", "Currency", "Merchant Name", "Transaction Date & Time",
    "Transaction ID", "Payment ID", "Category", "Location", "Settlement Time",
    "Payment Amount", "Payment Currency", "Total Mark-up over ECB Rate",
    "Points Earned",
]
WD_HEADER = ["Uid", "Date", "Type", "Asset", "Chain", "Amount", "Tx ID", "Status",
             "Received Address"]

CARD_MEMBER = re.compile(r"bybitCardTransactionHistory.*\.csv$", re.I)
WD_MEMBER = re.compile(r"withdrawDepositHistory.*\.csv$", re.I)
UID_PREAMBLE = re.compile(r"UID:\s*(\d+)")
PERIOD_IN_NAME = re.compile(r"(\d{8})_(\d{8})")
DATETIME = re.compile(r"^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2}:\d{2})$")

CARD_TYPES = {"DEDUCT", "FREEZE", "UNFREEZE"}
CARD_ORDER_TYPES = {"Authorization", "Authorization Reversal", "Transaction Completed"}
CARD_STATUSES = {"SUCCESS", "FAIL"}
WD_OPERATIONS = {"Deposit": "Replenishment", "Withdraw": "Transfers"}
WD_FINAL_STATUSES = {"Completed", "Transferred successfully"}

CURRENCY = "USD"  # валюта карточного счёта ByBit; крипто-строки несут свой актив


def num(text: str) -> Decimal:
    return Decimal(text.strip() or "0")


def money(value: Decimal | None) -> float | None:
    return None if value is None else float(value)


@dataclass
class Row:
    date: str
    time: str | None
    operation: str
    description: str
    reference: str
    amount: Decimal
    currency: str
    income: Decimal
    expense: Decimal
    exchange_rate: dict | None = None

    def to_json(self) -> dict:
        return {
            "date": self.date,
            "time": self.time,
            "operation": self.operation,
            "description": self.description,
            "reference": self.reference,
            "auth_code": None,
            "exchange_rate": self.exchange_rate,
            "amount": money(self.amount),
            "currency": self.currency,
            "income": money(self.income),
            "expense": money(self.expense),
        }


def read_export_csv(text: str, expected_header: list[str], source: str,
                    problems: list[str]) -> tuple[str | None, list[dict]]:
    """Преамбула с UID + DictReader; несовпадение заголовков — стоп-сигнал."""
    lines = text.splitlines()
    if not lines:
        raise ValueError(f"{source}: пустой файл")
    uid_match = UID_PREAMBLE.search(lines[0])
    body = lines[1:] if uid_match or "Company Name" in lines[0] else lines
    reader = csv.reader(io.StringIO("\n".join(body)))
    header = next(reader, None)
    if header != expected_header:
        raise ValueError(
            f"{source}: заголовок не совпадает с ожидаемым форматом ByBit "
            f"(получено: {header[:5] if header else header}…)")
    rows = []
    for index, values in enumerate(reader, start=3):
        if not any(values):
            continue
        if len(values) != len(expected_header):
            problems.append(f"MISMATCH: {source} строка {index}: "
                            f"{len(values)} полей вместо {len(expected_header)}")
            continue
        rows.append(dict(zip(expected_header, values)))
    return uid_match.group(1) if uid_match else None, rows


def split_datetime(value: str, source: str, problems: list[str]) -> tuple[str, str | None]:
    match = DATETIME.match(value.strip())
    if not match:
        problems.append(f"MISMATCH: {source}: не разобрана дата '{value}'")
        return "1970-01-01", None
    return match.group(1), match.group(2)


def parse_card(text: str, source: str, problems: list[str]) -> tuple[str | None, list[Row]]:
    uid, raw = read_export_csv(text, CARD_HEADER, source, problems)
    rows: list[Row] = []
    for record in raw:
        kind = (record["Type"], record["Order Type"], record["Status"])
        if record["Type"] not in CARD_TYPES or record["Order Type"] not in CARD_ORDER_TYPES \
                or record["Status"] not in CARD_STATUSES:
            problems.append(f"MISMATCH: {source}: неизвестная комбинация {kind}")
            continue
        try:
            transaction_amount = num(record["Transaction Amount"])
            fee = num(record["Total Fee"])
            payment_amount = num(record["Payment Amount"])
        except InvalidOperation:
            problems.append(f"MISMATCH: {source}: не разобрана сумма в {record['Transaction ID']}")
            continue
        if record["Status"] != "SUCCESS":
            continue
        if record["Type"] in ("FREEZE", "UNFREEZE"):
            continue  # холды — «в обработке», не импортируются (FR-4.9)
        # DEDUCT: реальное списание
        if transaction_amount < 0 or fee < 0:
            problems.append(f"MISMATCH: {source}: отрицательная сумма в {record['Transaction ID']}")
        date, time = split_datetime(record["Transaction Date & Time"], source, problems)
        merchant = record["Merchant Name"].strip()
        exchange_rate = None
        amount, currency = transaction_amount, record["Currency"]
        if record["Payment Currency"] != record["Currency"] and payment_amount:
            amount, currency = payment_amount, record["Payment Currency"]
            exchange_rate = {
                "rate": float(transaction_amount / payment_amount),
                "from": record["Payment Currency"],
                "to": record["Currency"],
            }
        rows.append(Row(
            date=date, time=time, operation="Payment",
            description=merchant or record["Category"],
            reference=record["Transaction ID"],
            amount=amount, currency=currency,
            income=Decimal(0), expense=transaction_amount,
            exchange_rate=exchange_rate,
        ))
        if fee:
            rows.append(Row(
                date=date, time=time, operation="Fee",
                description=f"Card fee: {merchant}".strip(),
                reference=f"{record['Transaction ID']}:fee",
                amount=fee, currency=record["Currency"],
                income=Decimal(0), expense=fee,
            ))
    return uid, rows


def parse_wd(text: str, source: str, problems: list[str]) -> tuple[str | None, list[Row]]:
    uid, raw = read_export_csv(text, WD_HEADER, source, problems)
    rows: list[Row] = []
    for record in raw:
        operation = WD_OPERATIONS.get(record["Type"])
        if operation is None:
            problems.append(f"MISMATCH: {source}: неизвестный тип '{record['Type']}'")
            continue
        if record["Status"] not in WD_FINAL_STATUSES:
            problems.append(f"MISMATCH: {source}: неизвестный статус '{record['Status']}'")
            continue
        try:
            amount = num(record["Amount"])
        except InvalidOperation:
            problems.append(f"MISMATCH: {source}: не разобрана сумма '{record['Amount']}'")
            continue
        if amount < 0:
            problems.append(f"MISMATCH: {source}: отрицательная сумма '{record['Amount']}'")
        date, time = split_datetime(record["Date"], source, problems)
        incoming = record["Type"] == "Deposit"
        rows.append(Row(
            date=date, time=time, operation=operation,
            description=f"{record['Type']} {record['Asset']} ({record['Chain']})",
            reference=record["Tx ID"],
            amount=amount, currency=record["Asset"],
            income=amount if incoming else Decimal(0),
            expense=Decimal(0) if incoming else amount,
        ))
    return uid, rows


def load_inputs(path: Path) -> dict[str, str]:
    """kind ('card'/'wd') -> текст CSV; формат определяется по содержимому."""
    if zipfile.is_zipfile(path):
        inputs: dict[str, str] = {}
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                kind = "card" if CARD_MEMBER.search(name) else \
                    "wd" if WD_MEMBER.search(name) else None
                if kind:
                    inputs[kind] = archive.read(name).decode("utf-8-sig")
        if not inputs:
            raise ValueError("В архиве нет CSV-файлов выгрузки ByBit")
        return inputs
    text = path.read_text(encoding="utf-8-sig")
    header_line = text.splitlines()[1] if len(text.splitlines()) > 1 else ""
    if header_line.startswith(",".join(CARD_HEADER[:3])):
        return {"card": text}
    if header_line.startswith(",".join(WD_HEADER[:3])):
        return {"wd": text}
    raise ValueError("Не похоже на выгрузку ByBit (ни zip, ни известный CSV)")


def resolve_period(path: Path, rows: list[Row]) -> tuple[str, str]:
    match = PERIOD_IN_NAME.search(path.name)
    if match:
        raw_from, raw_to = match.groups()
        return (f"{raw_from[:4]}-{raw_from[4:6]}-{raw_from[6:]}",
                f"{raw_to[:4]}-{raw_to[4:6]}-{raw_to[6:]}")
    dates = sorted(row.date for row in rows)
    if not dates:
        raise ValueError("Ни одной операции и нет периода в имени файла")
    return dates[0], dates[-1]


def verify_structure(rows: list[Row], problems: list[str]) -> None:
    seen: set[str] = set()
    for row in rows:
        if not row.reference:
            problems.append(f"MISMATCH: пустой reference у операции {row.date} '{row.description}'")
        elif row.reference in seen:
            problems.append(f"MISMATCH: дубликат reference {row.reference}")
        seen.add(row.reference)


def to_json(source: str, uid: str | None, rows: list[Row],
            period: tuple[str, str]) -> dict:
    def total(operations: set[str], side) -> float:
        return money(sum((side(row) for row in rows if row.operation in operations), Decimal(0)))

    return {
        "source": source,
        "bank": {"name": "ByBit", "bic": None},
        "statement": {
            "type": "Asset History",
            "generated_at": None,
            "period_from": period[0],
            "period_to": period[1],
        },
        "holder": {"name": None, "iin": None},
        "accounts": [{
            "account": uid or "BYBIT",
            "currency": CURRENCY,
            "summary": {
                # балансов и печатных итогов в выгрузке нет — суммы выведены из строк
                "balance_start": None,
                "balance_end": None,
                "deposits": total({"Replenishment"}, lambda r: r.income),
                "transfers": total({"Transfers"}, lambda r: r.expense),
                "purchases": total({"Payment"}, lambda r: r.expense),
                "withdrawals": 0,
                "fees": total({"Fee"}, lambda r: r.expense),
                "others": 0,
                "amount_in_process": None,
                "balance_total": None,
            },
            "transactions": [row.to_json() for row in rows],
        }],
    }


def redact(document: dict) -> dict:
    """Скрыть UID аккаунта (последние 4 цифры остаются для маппинга)."""
    for account in document["accounts"]:
        account["account"] = f"****{account['account'][-4:]}"
    return document


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Конвертировать выгрузку ByBit (zip или CSV) в JSON.")
    parser.add_argument("statement", type=Path, help="путь к zip-архиву или CSV")
    parser.add_argument("-o", "--output", type=Path,
                        help="файл для записи (по умолчанию — stdout)")
    parser.add_argument("--redact", action="store_true", help="скрыть UID аккаунта")
    parser.add_argument("--indent", type=int, default=2,
                        help="отступ в JSON (0 — компактно)")
    parser.add_argument("--no-verify", action="store_true",
                        help="не выполнять структурную сверку")
    args = parser.parse_args(argv)

    if not args.statement.is_file():
        print(f"Файл не найден: {args.statement}", file=sys.stderr)
        return 2

    problems: list[str] = []
    try:
        inputs = load_inputs(args.statement)
        uid = None
        rows: list[Row] = []
        if "card" in inputs:
            uid, card_rows = parse_card(inputs["card"], "card CSV", problems)
            rows.extend(card_rows)
        if "wd" in inputs:
            wd_uid, wd_rows = parse_wd(inputs["wd"], "withdraw/deposit CSV", problems)
            rows.extend(wd_rows)
            if uid and wd_uid and uid != wd_uid:
                problems.append(f"MISMATCH: разные UID в файлах архива ({uid} != {wd_uid})")
            uid = uid or wd_uid
        rows.sort(key=lambda row: (row.date, row.time or ""))
        period = resolve_period(args.statement, rows)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2

    if not args.no_verify:
        verify_structure(rows, problems)
    else:
        problems = []

    document = to_json(args.statement.name, uid, rows, period)
    if args.redact:
        document = redact(document)

    payload = json.dumps(document, ensure_ascii=False, indent=args.indent or None)
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
        print(f"{len(rows)} транзакций -> {args.output}", file=sys.stderr)
    else:
        print(payload)

    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    if not args.no_verify:
        print("Структурная сверка пройдена (печатных итогов в выгрузке нет).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
