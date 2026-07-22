"""RSHB (Rosselkhozbank) account statement PDF -> JSON converter.

    python parsers/rshb.py statement.pdf -o out.json

Landscape A4, one ruled table per page, parsed via pdfplumber extract_tables
(the text layer wraps cell content mid-word, so flat text is unusable). Two
layout variants exist and are distinguished by the table header row, which is
printed once on the first page:

- card-account variant ("ВЫПИСКА ПО КАРТОЧНОМУ СЧЕТУ"): 9 columns — posting
  date, operation date, expense, income, description, operation currency,
  amount in operation currency, commission, masked card number; expense
  amounts are printed negative;
- account variant ("ВЫПИСКА ПО СЧЕТУ"): 7 columns — operation date, expense,
  income, description, correspondent account, correspondent name,
  correspondent bank BIC; expense amounts are printed positive.

Shared traits: amounts use space thousand separators and a decimal comma;
opening/closing balances are labelled lines above/below the table; the
confirmed-operations block ends with a totals row (empty date cell, expense
and income control sums). The card variant may be followed by a
pending-operations section — everything after the totals row is ignored.
Header cells and long words wrap mid-word inside cells, therefore all
matching (header aliases, operation keywords, ids inside descriptions) is
done on text with whitespace removed.

Rows have no reference column: an id is mined from the description
(REF.<digits>, SBP C2C operation id, transfer id) and otherwise synthesized
as "<date>:<seq within date>". Time is taken from the SBP operation stamp in
the description when its date matches the row date.

Verification: opening balance + sum(income) - sum(expense) must equal the
printed closing balance, and per-column sums must match the totals row.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import pdfplumber

TITLE = re.compile(
    r"ВЫПИСКА ПО (КАРТОЧНОМУ )?СЧ[ЕЁ]ТУ\s+(\d{20})\s+"
    r"за период с\s+(\d{2}\.\d{2}\.\d{4})\s+по\s+(\d{2}\.\d{2}\.\d{4})")
DATE_CELL = re.compile(r"^\d{2}\.\d{2}\.\d{4}$")
NUMBER = re.compile(r"^-?\d+(?:\.\d+)?$")
MONEY = r"(-?[\d\s  ]+,\d{2})"
BALANCE_START = re.compile(r"Сумма входящего остатка[^:\n]*:\s*" + MONEY)
BALANCE_END = re.compile(r"Исходящий остаток[^:\n]*:\s*" + MONEY)
BALANCE_AVAILABLE = re.compile(r"Сумма доступного остатка[^:\n]*:\s*" + MONEY)
ACCOUNT_IN_TEXT = re.compile(r"\b\d(?:\s?\d){15,}\b")   # 16+ digits, may wrap
PHONE = re.compile(r"\+7[\s()\-]*\d[\d\s()\-]{8,}\d")

# id patterns applied to compacted (whitespace-free) description text
REF_ID = re.compile(r"REF\.?(\d+)")
SBP_ID = re.compile(r"[СC]2[СC]([0-9A-Za-z]+)")         # Cyrillic or Latin C
TRANSFER_ID = re.compile(r"IDперевода(\d+)")
SBP_STAMP = re.compile(r"Датаоперации(\d{2}\.\d{2}\.\d{4})(\d{2}:\d{2}:\d{2})")

# table header aliases, compacted: cells wrap mid-word ("Содержани е операции")
HEADER_ALIASES = {
    "датапроведенияоперации": "posted",
    "датасовершенияоперации": "date",
    "расходпосчету": "expense",
    "приходпосчету": "income",
    "содержаниеоперации": "description",
    "валютаоперации": "op_currency",
    "суммаввалютеоперации": "op_amount",
    "комиссияввалютеоперации": "commission",
    "№карты": "card",
    "корреспондирующийсчет": "corr_account",
    "корреспондент": "corr_name",
    "банккорреспондента": "corr_bank",
}

# Russian currency names -> ISO 4217; checked in order (белорусский рубль
# must win over рубль), fallback is the currency code inside the account
# number (digits 6-8 of a Russian account number)
CURRENCY_NAMES = [
    ("белорус", "BYN"), ("рубл", "RUB"), ("долл", "USD"), ("евро", "EUR"),
    ("юан", "CNY"), ("тенге", "KZT"), ("фунт", "GBP"), ("франк", "CHF"),
]
CURRENCY_CODES = {"810": "RUB", "840": "USD", "978": "EUR",
                  "156": "CNY", "398": "KZT", "933": "BYN"}


def clean(value: str | None) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def compact(value: str | None) -> str:
    """Whitespace-free text — the only reliable form for keyword matching,
    because cells wrap mid-word."""
    return re.sub(r"\s+", "", value or "")


def number(value: str | None) -> float:
    text = compact(value).replace(" ", "").replace(" ", "")
    text = text.replace(",", ".")
    return float(text) if NUMBER.match(text) else 0.0


def iso_date(ddmmyyyy: str) -> str:
    day, month, year = ddmmyyyy.split(".")
    return f"{year}-{month}-{day}"


def currency_iso(name: str | None, account: str) -> str:
    lowered = (name or "").lower()
    for needle, code in CURRENCY_NAMES:
        if needle in lowered:
            return code
    return CURRENCY_CODES.get(account[5:8], clean(name) or "RUB")


def parse_header(text: str) -> dict:
    title = TITLE.search(text)
    if not title:
        raise ValueError(
            "Not an RSHB statement (title/account/period line not found)")
    card_variant, account = bool(title.group(1)), title.group(2)

    def find(pattern: str) -> str | None:
        match = re.search(pattern, text)
        return clean(match.group(1)) if match else None

    generated = find(r"Дата выписки:\s*(\d{2}\.\d{2}\.\d{4})")
    statement_type = find(r"Тип сч[её]та:\s*([^\n]+)")
    if not statement_type:
        statement_type = "Карточный счёт" if card_variant else "Счёт"
    balance_start = BALANCE_START.search(text)
    balance_end = BALANCE_END.search(text)
    available = BALANCE_AVAILABLE.search(text)
    return {
        "account": account,
        "currency": currency_iso(find(r"Валюта сч[её]та:\s*([^\n]+)"), account),
        "type": statement_type,
        "generated_at": f"{iso_date(generated)}T00:00:00" if generated else None,
        "period_from": iso_date(title.group(3)),
        "period_to": iso_date(title.group(4)),
        "holder": find(r"Владелец сч[её]та:\s*([^\n]+)"),
        "balance_start": number(balance_start.group(1)) if balance_start else None,
        "balance_end": number(balance_end.group(1)) if balance_end else None,
        "balance_available": number(available.group(1)) if available else None,
    }


def map_columns(cells: list[str]) -> dict[str, int] | None:
    columns = {}
    for index, cell in enumerate(cells):
        key = HEADER_ALIASES.get(compact(cell).lower())
        if key:
            columns[key] = index
    required = {"date", "expense", "income", "description"}
    return columns if required <= columns.keys() else None


def classify(description: str, income: float) -> str:
    """Map the bank's wording onto the contract's fixed vocabulary."""
    if income:
        return "Replenishment"
    text = compact(description).lower()
    if "комисси" in text:
        return "Commission"
    if "наличн" in text or "банкомат" in text or "atm" in text:
        return "Withdrawals"
    if "перевод" in text or "transfer" in text or "сбп" in text:
        return "Transfers"
    return "Payment"


def parse_transaction(cells: list[str], columns: dict[str, int],
                      account_currency: str) -> dict:
    def cell(key: str) -> str:
        index = columns.get(key)
        return clean(cells[index]) if index is not None and index < len(cells) else ""

    date = iso_date(cell("date"))
    expense = abs(number(cell("expense")))   # card variant prints negatives
    income = abs(number(cell("income")))
    description = cell("description")
    dense = compact(cell("description"))

    reference = None
    for pattern in (REF_ID, SBP_ID, TRANSFER_ID):
        match = pattern.search(dense)
        if match:
            reference = match.group(1)
            break

    time = None
    stamp = SBP_STAMP.search(dense)
    if stamp and iso_date(stamp.group(1)) == date:
        time = stamp.group(2)

    corr = ", ".join(part for part in (cell("corr_name"), cell("corr_account"))
                     if part)
    if corr:
        description = f"{description} ({corr})"

    amount = expense or income
    currency = account_currency
    exchange_rate = None
    op_amount = abs(number(cell("op_amount")))
    op_currency_name = cell("op_currency")
    if op_currency_name:
        op_currency = currency_iso(op_currency_name, "")
        if op_currency != account_currency and op_amount:
            exchange_rate = {"rate": round((expense or income) / op_amount, 6),
                             "from": op_currency, "to": account_currency}
            amount, currency = op_amount, op_currency

    return {
        "date": date,
        "time": time,
        "operation": classify(description, income),
        "description": description,
        "reference": reference,
        "auth_code": None,
        "exchange_rate": exchange_rate,
        "amount": amount,
        "currency": currency,
        "income": income,
        "expense": expense,
    }


def assign_references(rows: list[dict]) -> None:
    """Synthesize "<date>:<seq>" where the description carries no id, and
    make sure every reference is unique within the statement."""
    per_date: dict[str, int] = {}
    seen: set[str] = set()
    for row in rows:
        per_date[row["date"]] = per_date.get(row["date"], 0) + 1
        reference = row["reference"] or f"{row['date']}:{per_date[row['date']]}"
        while reference in seen:
            reference += "+"
        seen.add(reference)
        row["reference"] = reference


def parse_statement(path: Path) -> dict:
    rows: list[dict] = []
    printed_totals: tuple[float, float] | None = None
    columns: dict[str, int] | None = None

    with pdfplumber.open(path) as pdf:
        full_text = "\n".join(page.extract_text() or "" for page in pdf.pages)
        header = parse_header(full_text)

        for page in pdf.pages:
            for table in page.extract_tables() or []:
                for raw in table:
                    cells = [clean(c) for c in raw]
                    mapped = map_columns(cells)
                    if mapped:
                        columns = mapped
                        continue
                    if columns is None or printed_totals is not None:
                        continue          # before the header / pending block
                    date_cell = cells[columns["date"]] if columns["date"] < len(cells) else ""
                    if DATE_CELL.match(date_cell):
                        row = parse_transaction(cells, columns, header["currency"])
                        if row["income"] or row["expense"]:
                            rows.append(row)
                    elif not date_cell and not clean(cells[columns["description"]]):
                        expense = number(cells[columns["expense"]])
                        income = number(cells[columns["income"]])
                        if expense or income:   # control sums close the block
                            printed_totals = (abs(expense), abs(income))
                    elif rows and cells[columns["description"]]:
                        # defensive: a row split by a page break — not seen in
                        # samples, but cheap to glue back
                        rows[-1]["description"] = clean(
                            f"{rows[-1]['description']} {cells[columns['description']]}")

    if columns is None:
        raise ValueError("Not an RSHB statement (transaction table not found)")
    assign_references(rows)
    return {"header": header, "rows": rows, "printed_totals": printed_totals}


def verify(parsed: dict) -> list[str]:
    header, rows = parsed["header"], parsed["rows"]
    problems = []
    income = round(sum(row["income"] for row in rows), 2)
    expense = round(sum(row["expense"] for row in rows), 2)

    totals = parsed["printed_totals"]
    if totals is None:
        if rows:
            problems.append("MISMATCH: totals row not found "
                            "but the statement has transactions")
    else:
        if abs(expense - totals[0]) >= 0.01:
            problems.append(f"MISMATCH: sum of expenses {expense} "
                            f"!= printed total {totals[0]}")
        if abs(income - totals[1]) >= 0.01:
            problems.append(f"MISMATCH: sum of income {income} "
                            f"!= printed total {totals[1]}")

    if header["balance_start"] is None or header["balance_end"] is None:
        problems.append("MISMATCH: opening/closing balance line not found")
    else:
        computed = round(header["balance_start"] + income - expense, 2)
        if abs(computed - header["balance_end"]) >= 0.01:
            problems.append(f"MISMATCH: computed closing balance {computed} "
                            f"!= printed {header['balance_end']}")

    both = [row for row in rows if row["income"] and row["expense"]]
    if both:
        problems.append(f"MISMATCH: {len(both)} rows have both income and "
                        f"expense non-zero (first: {both[0]['date']})")
    return problems


def to_json(parsed: dict, source: str) -> dict:
    header, rows = parsed["header"], parsed["rows"]

    def total(operation: str) -> float:
        return round(sum(r["expense"] for r in rows
                         if r["operation"] == operation), 2)

    balance_end = header["balance_end"]
    return {
        "source": source,
        "bank": {"name": "Россельхозбанк", "bic": None},
        "statement": {
            "type": header["type"],
            "generated_at": header["generated_at"],
            "period_from": header["period_from"],
            "period_to": header["period_to"],
        },
        "holder": {"name": header["holder"], "iin": None, "card_number": None},
        "accounts": [{
            "account": header["account"],
            "currency": header["currency"],
            "summary": {
                "balance_start": header["balance_start"],
                "balance_end": balance_end,
                "deposits": round(sum(r["income"] for r in rows), 2),
                "transfers": total("Transfers"),
                "purchases": total("Payment"),
                "withdrawals": total("Withdrawals"),
                "fees": round(total("Commission") + total("Fee"), 2),
                "others": None,
                "amount_in_process": None,
                "balance_total": (header["balance_available"]
                                  if header["balance_available"] is not None
                                  else balance_end),
            },
            "transactions": rows_to_json(rows),
        }],
    }


def rows_to_json(rows: list[dict]) -> list[dict]:
    return [{key: row[key] for key in (
        "date", "time", "operation", "description", "reference", "auth_code",
        "exchange_rate", "amount", "currency", "income", "expense")}
        for row in rows]


def redact(document: dict) -> dict:
    """Mask the holder name, account numbers and phone numbers. The last 4
    digits of accounts survive so own-account transfers still match up."""
    def mask(match: re.Match) -> str:
        return f"****{compact(match.group(0))[-4:]}"

    document["holder"]["name"] = None
    for account in document["accounts"]:
        account["account"] = f"****{account['account'][-4:]}"
        for row in account["transactions"]:
            text = ACCOUNT_IN_TEXT.sub(mask, row["description"])
            row["description"] = PHONE.sub("+7***", text)
    return document


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert an RSHB (Rosselkhozbank) PDF statement to JSON.")
    parser.add_argument("pdf", type=Path, help="path to the PDF statement")
    parser.add_argument("-o", "--output", type=Path,
                        help="write JSON to a file (default: stdout)")
    parser.add_argument("--redact", action="store_true",
                        help="mask holder name, account and phone numbers")
    parser.add_argument("--indent", type=int, default=2,
                        help="JSON indent (0 for compact)")
    parser.add_argument("--no-verify", action="store_true",
                        help="skip checksum verification")
    args = parser.parse_args(argv)

    if not args.pdf.is_file():
        print(f"File not found: {args.pdf}", file=sys.stderr)
        return 2

    try:
        parsed = parse_statement(args.pdf)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    except Exception as error:          # unreadable/broken PDF
        print(f"Cannot read {args.pdf}: {error}", file=sys.stderr)
        return 2

    problems = [] if args.no_verify else verify(parsed)
    document = to_json(parsed, args.pdf.name)
    if args.redact:
        document = redact(document)

    payload = json.dumps(document, ensure_ascii=False,
                         indent=args.indent or None)
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
        print(f"{len(parsed['rows'])} transactions -> {args.output}",
              file=sys.stderr)
    else:
        print(payload)

    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    if not args.no_verify:
        print("Checksum verification against the statement's own totals "
              "passed.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
