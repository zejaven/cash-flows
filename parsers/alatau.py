"""Конвертер выписки Alatau City Bank из PDF в JSON.

    python parse_statement.py "docs/Bank statement-2026-07-20 185719.pdf" -o out.json

Разбор идёт по ячейкам таблицы (pdfplumber), а не по плоскому тексту: в текстовом
слое этого PDF ячейки строки идут не всегда слева направо, а пробел работает
разделителем тысяч, из-за чего колонки income/expense неразличимы.

Результат сверяется с контрольными суммами из самого документа: сумма приходов
должна совпасть с Deposits, а `баланс на начало + приходы - расходы` — с балансом
на конец периода. При расхождении скрипт возвращает ненулевой код возврата.

Сторно (например, отменённое снятие в банкомате) печатается строкой
Replenishment с тем же Reference, суммой и валютой операции, что и исходный
расход. В таких строках банк печатает в "Income in account currency" сумму в
валюте операции (800 NZD -> "800" в USD-счёте), тогда как Deposits в сводке
посчитан верно. Поэтому приход сторно берётся из расхода исходной строки, а
если её в выписке нет и валюта операции совпадает с валютой счёта — из Amount.

Структура документа описана в docs/bank-statement-structure.md.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import pdfplumber

# Метки сводного блока -> ключи в JSON
SUMMARY_KEYS = {
    "Deposits": "deposits",
    "Transfers": "transfers",
    "Purchases": "purchases",
    "Withdrawals": "withdrawals",
    "Fees": "fees",
    "Others": "others",
}

ACCOUNT_HEADER = re.compile(r"^(KZ\S+)\s+([A-Z]{3})$")
# Номер счёта вида KZ61998PB00012753905
ACCOUNT_NUMBER = re.compile(r"\bKZ\d{2}[0-9A-Z]{12,}\b")
TX_MARKER = re.compile(r"Account transactions:\s*(KZ\S+)\s+([A-Z]{3})")
DATE_CELL = re.compile(r"(\d{2}\.\d{2}\.\d{4})\s*(\d{2}:\d{2}:\d{2})?")
TIME_CELL = re.compile(r"^\d{2}:\d{2}:\d{2}$")
NUMBER = re.compile(r"^-?\d+(?:\.\d+)?$")
REFERENCE = re.compile(r"Reference:\s*(\S+)")
AUTH_CODE = re.compile(r"Authorization code:\s*(\S+)")
# "at the Bank's current exchange rate 485.40 KZT = 1 USD"
RATE = re.compile(r"exchange rate\s+([\d.]+)\s+([A-Z]{3})\s*=\s*1\s+([A-Z]{3})")

SUMMARY_COLUMNS = 2

# Шапка таблицы операций -> ключ колонки. Колонки ищутся по названиям, а не по
# позициям: с осени 2026 банк добавил "Transaction completion date" (8 колонок
# вместо 7), а "Date" переименовал в "Transaction date". Дата проводки
# игнорируется — дата и время операции остаются прежними, иначе разъедутся
# ключи дедупликации с ранее импортированными выписками.
COLUMN_ALIASES = {
    "date": "date",
    "transaction date": "date",
    "transaction completion date": "completed",
    "operation": "operation",
    "details": "details",
    "amount": "amount",
    "transaction currency": "currency",
    "income in account currency": "income",
    "expense in account currency": "expense",
}
REQUIRED_COLUMNS = {"date", "operation", "details", "amount", "currency",
                    "income", "expense"}
# Старый макет (7 колонок) — на случай таблицы без шапки до первой шапки
LEGACY_COLUMNS = {"date": 0, "operation": 1, "details": 2, "amount": 3,
                  "currency": 4, "income": 5, "expense": 6}


def clean(value: str | None) -> str:
    """Схлопнуть переносы внутри ячейки в одну строку."""
    return re.sub(r"\s+", " ", (value or "").replace("\n", " ")).strip()


def number(value: str | None) -> float:
    """Разобрать число, где разделитель тысяч — обычный или неразрывный пробел."""
    text = clean(value).replace(" ", "").replace(" ", "")
    return float(text) if NUMBER.match(text) else 0.0


def iso_date(ddmmyyyy: str) -> str:
    day, month, year = ddmmyyyy.split(".")
    return f"{year}-{month}-{day}"


def map_columns(row: list) -> dict[str, int] | None:
    """Строка-шапка таблицы операций -> {ключ: индекс}, иначе None."""
    columns = {}
    for index, cell in enumerate(row):
        key = COLUMN_ALIASES.get(clean(cell).lower())
        if key:
            columns[key] = index
    return columns if REQUIRED_COLUMNS <= columns.keys() else None


def cell(row: list, columns: dict[str, int], key: str) -> str | None:
    index = columns.get(key)
    return row[index] if index is not None and index < len(row) else None


def parse_header(text: str) -> dict:
    """Реквизиты выписки с первой страницы."""
    def find(pattern: str) -> str | None:
        match = re.search(pattern, text)
        return match.group(1).strip() if match else None

    period_from = find(r"for the period:\s*(\d{2}\.\d{2}\.\d{4})")
    period_to = find(r"for the period:\s*\d{2}\.\d{2}\.\d{4}\s*-\s*(\d{2}\.\d{2}\.\d{4})")
    generated = re.search(r"Date:\s*(\d{2}\.\d{2}\.\d{4})\s+(\d{2}:\d{2})", text)
    return {
        "bank": {
            "name": find(r'^"?([^"\n]+)"?\s+JSC'),
            "bic": find(r"BIC:\s*(\S+)"),
        },
        "statement": {
            "type": find(r"Statement:\s*(\w+)"),
            "generated_at": (f"{iso_date(generated.group(1))}T{generated.group(2)}"
                             if generated else None),
            "period_from": iso_date(period_from) if period_from else None,
            "period_to": iso_date(period_to) if period_to else None,
        },
        "holder": {
            "name": find(r"FULL NAME:\s*(.+)"),
            "iin": find(r"IIN:\s*(\d+)"),
            "card_number": find(r"Card number:\s*(\S+)"),
        },
    }


def parse_summary(table: list[list], summaries: dict, last_account: str | None):
    """Сводный блок счёта. Может разрываться на границе страниц — тогда у
    продолжения нет заголовка со счётом, и оно дописывается в предыдущий блок."""
    header = ACCOUNT_HEADER.match(clean(table[0][0]))
    if header:
        account = header.group(1)
        summaries.setdefault(account, {"currency": header.group(2)})
        rows = table[1:]
    elif last_account:
        account = last_account
        rows = table
    else:
        return last_account

    target = summaries[account]
    for row in rows:
        label, value = clean(row[0]), clean(row[1] if len(row) > 1 else "")
        if not label or not value:
            continue
        if label.startswith("Balance as of"):
            key = "balance_start" if "start" in label else "balance_end"
            target[key] = number(value)
        elif label.startswith("Amount in process"):
            target["amount_in_process"] = number(value)
        elif label.startswith("Total balance"):
            target["balance_total"] = number(value)
        elif label in SUMMARY_KEYS:
            target[SUMMARY_KEYS[label]] = number(value)
    return account


def parse_transaction(row: list, columns: dict[str, int], stamp: re.Match) -> dict:
    details = clean(cell(row, columns, "details"))
    reference = REFERENCE.search(details)
    auth = AUTH_CODE.search(details)
    rate = RATE.search(details)
    description = clean(
        re.sub(r"(Reference:|Authorization code:)\s*\S+", "", details))
    return {
        "date": iso_date(stamp.group(1)),
        "time": stamp.group(2),
        "operation": clean(cell(row, columns, "operation")) or None,
        "description": description,
        "reference": reference.group(1) if reference else None,
        "auth_code": auth.group(1) if auth else None,
        "exchange_rate": ({"rate": float(rate.group(1)),
                           "from": rate.group(2),
                           "to": rate.group(3)} if rate else None),
        "amount": number(cell(row, columns, "amount")),
        "currency": clean(cell(row, columns, "currency")) or None,
        "income": number(cell(row, columns, "income")),
        "expense": number(cell(row, columns, "expense")),
    }


def merge_continuation(row: list, columns: dict[str, int], previous: dict) -> None:
    """Разрыв страницы режет транзакцию надвое: внизу страницы остаётся голова
    (дата, тип, суммы), наверху следующей — хвост (время и остаток описания)."""
    head = clean(cell(row, columns, "date"))
    if TIME_CELL.match(head) and not previous["time"]:
        previous["time"] = head
    tail = clean(cell(row, columns, "details"))
    if not tail:
        return
    reference = REFERENCE.search(tail)
    if reference and not previous["reference"]:
        previous["reference"] = reference.group(1)
    auth = AUTH_CODE.search(tail)
    if auth and not previous["auth_code"]:
        previous["auth_code"] = auth.group(1)
    rest = clean(re.sub(r"(Reference:|Authorization code:)\s*\S+", "", tail))
    if rest:
        previous["description"] = clean(f"{previous['description']} {rest}")
        rate = RATE.search(previous["description"])
        if rate and not previous["exchange_rate"]:
            previous["exchange_rate"] = {"rate": float(rate.group(1)),
                                         "from": rate.group(2),
                                         "to": rate.group(3)}


def fix_reversals(rows: list[dict], account_currency: str | None) -> None:
    """Исправить приход в строках сторно (см. docstring модуля)."""
    originals = {(row["reference"], row["amount"], row["currency"]): row["expense"]
                 for row in rows if row["expense"] and row["reference"]}
    for row in rows:
        if row["operation"] != "Replenishment" or not row["reference"]:
            continue
        original = originals.get((row["reference"], row["amount"], row["currency"]))
        if original is not None:
            row["income"] = original
        elif row["currency"] == account_currency:
            row["income"] = row["amount"]


def parse_statement(path: Path) -> dict:
    summaries: dict[str, dict] = {}
    transactions: dict[str, list] = {}
    order: list[str] = []
    header: dict = {}
    current: str | None = None
    last_summary: str | None = None
    columns: dict[str, int] | None = None   # шапка печатается не на каждой странице

    with pdfplumber.open(path) as pdf:
        for index, page in enumerate(pdf.pages):
            text = page.extract_text() or ""
            if index == 0:
                header = parse_header(text)
            marker = TX_MARKER.search(text)
            if marker:
                current = str(marker.group(1))
                transactions.setdefault(current, [])
                if current not in order:
                    order.append(current)

            for table in page.extract_tables() or []:
                width = max(len(row) for row in table)
                if width == SUMMARY_COLUMNS:
                    last_summary = parse_summary(table, summaries, last_summary)
                elif width > SUMMARY_COLUMNS and current:
                    for row in table:
                        header_columns = map_columns(row)
                        if header_columns:
                            columns = header_columns
                            continue
                        active = columns or LEGACY_COLUMNS
                        if width != max(active.values()) + 1:
                            break             # не таблица операций
                        stamp = DATE_CELL.match(clean(cell(row, active, "date")))
                        if stamp:
                            transactions[current].append(
                                parse_transaction(row, active, stamp))
                        elif transactions[current]:
                            merge_continuation(row, active, transactions[current][-1])

    accounts = []
    for account in order or list(summaries):
        summary = dict(summaries.get(account, {}))
        currency = summary.pop("currency", None)
        fix_reversals(transactions.get(account, []), currency)
        accounts.append({
            "account": account,
            "currency": currency,
            "summary": summary,
            "transactions": transactions.get(account, []),
        })
    return {"source": path.name, **header, "accounts": accounts}


def verify(document: dict) -> list[str]:
    """Сверка с контрольными суммами самого документа."""
    problems = []
    for account in document["accounts"]:
        name = f"{account['currency']} {account['account'][-4:]}"
        summary, rows = account["summary"], account["transactions"]
        if not summary:
            problems.append(f"{name}: сводный блок не найден")
            continue

        income = round(sum(row["income"] for row in rows), 2)
        expense = round(sum(row["expense"] for row in rows), 2)
        balance = round(summary.get("balance_start", 0.0) + income - expense, 2)
        expected = summary.get("balance_end")

        if expected is not None and abs(balance - expected) >= 0.01:
            problems.append(
                f"{name}: баланс не сходится — посчитано {balance}, "
                f"в выписке {expected}")
        deposits = summary.get("deposits")
        if deposits is not None and abs(income - deposits) >= 0.01:
            problems.append(
                f"{name}: приходы не сходятся — посчитано {income}, "
                f"Deposits {deposits}")
        incomplete = [row for row in rows
                      if not (row["date"] and row["time"] and row["operation"])]
        if incomplete:
            problems.append(
                f"{name}: {len(incomplete)} транзакций без даты, времени или типа "
                f"(первая: {incomplete[0]['date']} {incomplete[0]['description'][:40]})")
    return problems


def redact(document: dict) -> dict:
    """Убрать личные идентификаторы (ИИН, номера счетов).

    Последние 4 цифры счёта сохраняются и в поле account, и в описаниях —
    иначе переводы между своими счетами перестают сопоставляться."""
    document["holder"]["name"] = None
    document["holder"]["iin"] = None
    for account in document["accounts"]:
        account["account"] = f"****{account['account'][-4:]}"
        for row in account["transactions"]:
            row["description"] = ACCOUNT_NUMBER.sub(
                lambda m: f"****{m.group(0)[-4:]}", row["description"])
    return document


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Конвертировать PDF-выписку Alatau City Bank в JSON.")
    parser.add_argument("pdf", type=Path, help="путь к PDF-выписке")
    parser.add_argument("-o", "--output", type=Path,
                        help="файл для записи (по умолчанию — stdout)")
    parser.add_argument("--redact", action="store_true",
                        help="убрать ИИН, имя и номера счетов")
    parser.add_argument("--indent", type=int, default=2,
                        help="отступ в JSON (0 — компактно)")
    parser.add_argument("--no-verify", action="store_true",
                        help="не сверять с контрольными суммами выписки")
    args = parser.parse_args(argv)

    if not args.pdf.is_file():
        print(f"Файл не найден: {args.pdf}", file=sys.stderr)
        return 2

    document = parse_statement(args.pdf)
    if args.redact:
        document = redact(document)

    problems = [] if args.no_verify else verify(document)
    total = sum(len(a["transactions"]) for a in document["accounts"])

    payload = json.dumps(document, ensure_ascii=False,
                         indent=args.indent or None)
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
        print(f"{total} транзакций по {len(document['accounts'])} счетам "
              f"-> {args.output}", file=sys.stderr)
    else:
        print(payload)

    for problem in problems:
        print(f"РАСХОЖДЕНИЕ: {problem}", file=sys.stderr)
    if problems:
        print("Данные могли разобраться неверно — проверьте выписку вручную.",
              file=sys.stderr)
        return 1
    if not args.no_verify:
        print("Сверка с контрольными суммами выписки пройдена.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
