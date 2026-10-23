"""LOCKO-Bank (КБ «ЛОКО-Банк» (АО)) current-account statement PDF -> JSON.

    python parsers/locko.py "Выписка по текущему счету.pdf" -o out.json

Portrait A4 export from "ЛОКО Online". A labelled header block (account
number, account currency "RUR", opening date, holder — the name may wrap onto
the next line) is followed by "Входящий остаток на <date>: <amount> RUR", one
ruled six-column table — document number, date, correspondent, expense,
income, payment purpose — and a footer with the control sums "Всего
израсходовано" / "Всего зачислено" and "Исходящий остаток на <date>". The
table is read via pdfplumber extract_tables (cells wrap; flat text interleaves
the purpose column with the other ones). Amounts use space thousand
separators and a decimal comma.

Rows carry a bank document number ("004/0073/1", "00001"), which is not
unique (every outgoing SBP transfer is "00001"), so the reference is
"<date>:<doc no>" with a "+" suffix on collisions. The correspondent is
appended to the description in parentheses. No time is printed.

Verification: per-column sums against the "Всего израсходовано/зачислено"
lines, and opening balance + income - expense against the printed closing
balance.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import pdfplumber

DATE_CELL = re.compile(r"^\d{2}\.\d{2}\.\d{4}$")
NUMBER = re.compile(r"^-?\d+(?:\.\d+)?$")
MONEY = r"(-?[\d\s\xa0]+,\d{2})"
PERIOD = re.compile(r"за период с\s+(\d{2}\.\d{2}\.\d{4})\s+по\s+(\d{2}\.\d{2}\.\d{4})")
ACCOUNT = re.compile(r"Номер сч[её]та\s+(\d{20})")
CURRENCY = re.compile(r"Валюта сч[её]та\s+([A-Z]{3})")
HOLDER = re.compile(r"Владелец сч[её]та\s+(.+?)\s*Входящий остаток", re.S)
BALANCE_START = re.compile(r"Входящий остаток на [\d.]+:\s*" + MONEY)
BALANCE_END = re.compile(r"Исходящий остаток на [\d.]+:\s*" + MONEY)
TOTAL_EXPENSE = re.compile(r"Всего израсходовано:\s*" + MONEY)
TOTAL_INCOME = re.compile(r"Всего зачислено:\s*" + MONEY)
GENERATED = re.compile(r"ЛОКО Online\"?\s*(\d{2}\.\d{2}\.\d{4})\s+(\d{2}:\d{2})")
ACCOUNT_IN_TEXT = re.compile(r"\b\d(?:\s?\d){15,}\b")
PHONE_MASKED = re.compile(r"\*{2,}\d{2,4}")

HEADER = ["№док.", "дата", "корреспондент", "расход", "приход", "назначениеплатежа"]
CURRENCY_ALIASES = {"RUR": "RUB"}


def clean(value: str | None) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def compact(value: str | None) -> str:
    return re.sub(r"\s+", "", value or "")


def number(value: str | None) -> float:
    text = compact(value).replace(",", ".")
    return float(text) if NUMBER.match(text) else 0.0


def iso_date(ddmmyyyy: str) -> str:
    day, month, year = ddmmyyyy.split(".")
    return f"{year}-{month}-{day}"


def parse_header(text: str) -> dict:
    period = PERIOD.search(text)
    account = ACCOUNT.search(text)
    if "ВЫПИСКА ПО ТЕКУЩЕМУ СЧЕТУ" not in text.upper() or not period or not account:
        raise ValueError("Not a LOCKO-Bank statement (title/period/account not found)")

    def money(pattern: re.Pattern) -> float | None:
        match = pattern.search(text)
        return number(match.group(1)) if match else None

    currency = CURRENCY.search(text)
    holder = HOLDER.search(text)
    generated = GENERATED.search(text)
    raw_currency = currency.group(1) if currency else "RUR"
    return {
        "account": account.group(1),
        "currency": CURRENCY_ALIASES.get(raw_currency, raw_currency),
        "period_from": iso_date(period.group(1)),
        "period_to": iso_date(period.group(2)),
        "holder": clean(holder.group(1)) if holder else None,
        "generated_at": (f"{iso_date(generated.group(1))}T{generated.group(2)}:00"
                         if generated else None),
        "balance_start": money(BALANCE_START),
        "balance_end": money(BALANCE_END),
        "total_expense": money(TOTAL_EXPENSE),
        "total_income": money(TOTAL_INCOME),
    }


def classify(description: str, income: float) -> str:
    """Map the bank's wording onto the contract's fixed vocabulary."""
    if income:
        return "Replenishment"
    text = description.lower()
    if "комисси" in text:
        return "Commission"
    if "наличн" in text or "банкомат" in text:
        return "Withdrawals"
    if "перевод" in text or "сбп" in text:
        return "Transfers"
    return "Payment"


def parse_row(cells: list[str], currency: str) -> dict:
    doc_no, date, correspondent, expense, income, purpose = cells[:6]
    expense_value = abs(number(expense))
    income_value = abs(number(income))
    description = clean(purpose)
    correspondent = clean(correspondent)
    if correspondent:
        description = f"{description} ({correspondent})"
    return {
        "date": iso_date(date),
        "time": None,
        "operation": classify(clean(purpose), income_value),
        "description": description,
        "reference": f"{iso_date(date)}:{compact(doc_no)}",
        "auth_code": None,
        "exchange_rate": None,
        "amount": expense_value or income_value,
        "currency": currency,
        "income": income_value,
        "expense": expense_value,
    }


def parse_statement(path: Path) -> dict:
    rows: list[dict] = []
    table_seen = False
    with pdfplumber.open(path) as pdf:
        text = "\n".join(page.extract_text() or "" for page in pdf.pages)
        header = parse_header(text)
        for page in pdf.pages:
            for table in page.extract_tables() or []:
                for raw in table:
                    cells = [cell or "" for cell in raw]
                    if len(cells) < 6:
                        continue
                    if [compact(c).lower() for c in cells[:6]] == HEADER:
                        table_seen = True
                        continue
                    if DATE_CELL.match(clean(cells[1])):
                        rows.append(parse_row(cells, header["currency"]))
                    elif rows and clean(cells[5]) and not number(cells[3]) and not number(cells[4]):
                        # a row split by a page break: purpose continues
                        rows[-1]["description"] = clean(f"{rows[-1]['description']} {cells[5]}")
    if not table_seen:
        raise ValueError("Not a LOCKO-Bank statement (transaction table not found)")

    seen: set[str] = set()
    for row in rows:
        while row["reference"] in seen:
            row["reference"] += "+"
        seen.add(row["reference"])
    return {"header": header, "rows": rows}


def verify(parsed: dict) -> list[str]:
    header, rows = parsed["header"], parsed["rows"]
    problems = []
    income = round(sum(row["income"] for row in rows), 2)
    expense = round(sum(row["expense"] for row in rows), 2)
    for key, actual in (("total_expense", expense), ("total_income", income)):
        printed = header[key]
        if printed is None:
            problems.append(f"MISMATCH: {key} line not found")
        elif abs(actual - printed) >= 0.01:
            problems.append(f"MISMATCH: sum {key} {actual} != printed {printed}")
    if header["balance_start"] is None or header["balance_end"] is None:
        problems.append("MISMATCH: opening/closing balance line not found")
    else:
        computed = round(header["balance_start"] + income - expense, 2)
        if abs(computed - header["balance_end"]) >= 0.01:
            problems.append(f"MISMATCH: computed closing balance {computed} "
                            f"!= printed {header['balance_end']}")
    for row in rows:
        if bool(row["income"]) == bool(row["expense"]):
            problems.append(f"MISMATCH: row {row['reference']} must have exactly "
                            "one of income/expense")
    return problems


def to_json(parsed: dict, source: str) -> dict:
    header, rows = parsed["header"], parsed["rows"]

    def total(operation: str) -> float:
        return round(sum(r["expense"] for r in rows if r["operation"] == operation), 2)

    return {
        "source": source,
        "bank": {"name": "КБ «ЛОКО-Банк» (АО)", "bic": None},
        "statement": {
            "type": "Выписка по текущему счету",
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
                "balance_end": header["balance_end"],
                "deposits": round(sum(r["income"] for r in rows), 2),
                "transfers": total("Transfers"),
                "purchases": total("Payment"),
                "withdrawals": total("Withdrawals"),
                "fees": round(total("Commission") + total("Fee"), 2),
                "others": None,
                "amount_in_process": None,
                "balance_total": header["balance_end"],
            },
            "transactions": rows,
        }],
    }


def redact(document: dict) -> dict:
    """Mask the holder name and account numbers (last 4 digits survive)."""
    holder = document["holder"]["name"]
    document["holder"]["name"] = None
    for account in document["accounts"]:
        account["account"] = f"****{account['account'][-4:]}"
        for row in account["transactions"]:
            text = ACCOUNT_IN_TEXT.sub(lambda m: f"****{compact(m.group(0))[-4:]}",
                                       row["description"])
            if holder:
                text = text.replace(holder, "***")
            row["description"] = PHONE_MASKED.sub("***", text)
    return document


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert a LOCKO-Bank PDF account statement to JSON.")
    parser.add_argument("pdf", type=Path, help="path to the PDF statement")
    parser.add_argument("-o", "--output", type=Path,
                        help="write JSON to a file (default: stdout)")
    parser.add_argument("--redact", action="store_true",
                        help="mask holder name and account numbers")
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

    payload = json.dumps(document, ensure_ascii=False, indent=args.indent or None)
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
        print(f"{len(parsed['rows'])} transactions -> {args.output}", file=sys.stderr)
    else:
        print(payload)

    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    if not args.no_verify:
        print("Checksum verification against the statement's own totals passed.",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
