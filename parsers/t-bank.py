"""T-Bank (Russia) PDF statement to JSON converter.

    python parsers/t-bank.py <statement.pdf> -o out.json

Layout: portrait A4, English-localised "Bank Statement". Page 1 carries the
header block (statement number + generation date, holder name above the
"Home address" line, contract and personal account numbers, the available
balance as of the period end) followed by a six-column transaction table that
continues over subsequent pages, each repeating the column header. The final
page prints the period totals ("Replenishment:" / "Expenses:").

The table has no ruled lines, so rows are rebuilt from word coordinates:
column x-anchors are taken from the repeated header words and every word is
bucketed by its x0. A transaction occupies two or more visual lines — the
first holds both dates, both signed amounts and the start of the description,
the second holds both times and the description continuation (which may run
on for several more lines and across a page break). Lines are clustered by
their top coordinate with a small tolerance because the currency glyph sits
about a pixel above its own line.

Number-format quirks: transaction amounts use a space as the thousands
separator and a decimal *point*, while the totals on the last page use a
decimal *comma*; both are handled by one normaliser. Amount direction comes
from the printed +/- sign. The second amount column is the card-currency
amount and feeds income/expense; the first is the transaction currency (an
exchange rate is derived only when the two currencies differ — not observed
in the samples, where both columns always match).

Rows print no reference, so a stable one is synthesized as
"<statement number>:<row number>" (row order within a statement is fixed).

Self-verification: the sum of incomes and the sum of expenses are compared
with the statement's own printed totals; the closing balance is taken from
the header's available-balance figure and the opening balance is derived
from it, so only the two totals are independent checks.

Known unsupported cases (not present in the samples): multi-account or
multi-currency statements, pending-operation blocks, foreign-currency
rows (the exchange-rate derivation is untested).
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

DATE = re.compile(r"^\d{2}\.\d{2}\.\d{4}$")
TIME = re.compile(r"^\d{2}:\d{2}(?::\d{2})?$")
STATEMENT_NO = re.compile(r"№\s*(\S+)\s+(\d{2}\.\d{2}\.\d{4})")
PERIOD = re.compile(
    r"for the period from\s+(\d{2}\.\d{2}\.\d{4})\s+to\s+(\d{2}\.\d{2}\.\d{4})")
ACCOUNT = re.compile(r"Personal account number:\s*(\d+)")
CONTRACT = re.compile(r"Contract number:\s*(\d+)")
BALANCE = re.compile(
    r"available balance as\s+\d{2}\.\d{2}\.\d{4}:\s*([+-]?[\d  ]+[.,]\d{2})")
HOLDER = re.compile(r"^(.+)\r?\nHome address:", re.M)
BIC = re.compile(r"BIC\s+(\d{9})")
TOTAL_IN = re.compile(r"Replenishment:\s*([\d  ]+[.,]\d{2})")
TOTAL_OUT = re.compile(r"Expenses:\s*([\d  ]+[.,]\d{2})")
AMOUNT = re.compile(r"^([+-])([\d  ]+[.,]\d{2})\s*(\S+)$")
LONG_DIGITS = re.compile(r"(?:\+?\d[\d\-]{6,})")

CURRENCY_SYMBOLS = {"₽": "RUB", "$": "USD", "€": "EUR", "£": "GBP"}

# The bank's own description wording -> the contract's fixed operation
# vocabulary. Checked in order; first match wins.
OPERATION_PREFIXES = [
    ("Bank service payment", "Fee"),
    ("Transfer fee", "Fee"),
    ("Commission", "Commission"),
    ("Cash withdrawal", "Withdrawals"),
    ("ATM", "Withdrawals"),
    ("Payment", "Payment"),
    ("Replenishment", "Replenishment"),
    ("Getting a loan", "Replenishment"),
    ("Intrabank transfer", "Transfers"),
    ("Internal transfer", "Transfers"),
    ("External transfer", "Transfers"),
    ("External bank transfer", "Transfers"),
    ("Transfer", "Transfers"),
]

LINE_TOLERANCE = 2.5      # currency glyph sits ~1px above its line
COLUMN_SLACK = 3.0        # left-aligned columns; small jitter allowance


def number(text: str) -> Decimal:
    """Parse an amount with space/nbsp thousands and either decimal mark."""
    return Decimal(text.replace(" ", "").replace(" ", "").replace(",", "."))


def money(value: Decimal | None) -> float:
    return float(value) if value is not None else 0.0


def iso_date(ddmmyyyy: str) -> str:
    day, month, year = ddmmyyyy.split(".")
    return f"{year}-{month}-{day}"


def currency_code(symbol: str) -> str:
    return CURRENCY_SYMBOLS.get(symbol, symbol)


@dataclass
class Row:
    date: str                       # ISO
    time: str | None
    txn_amount: Decimal             # signed, transaction currency
    txn_currency: str
    card_amount: Decimal            # signed, card/account currency
    card_currency: str
    description: str
    card: str | None
    reference: str = ""

    def operation(self) -> str:
        for prefix, operation in OPERATION_PREFIXES:
            if self.description.startswith(prefix):
                return operation
        return "Replenishment" if self.card_amount > 0 else "Payment"

    def to_json(self) -> dict:
        income = self.card_amount if self.card_amount > 0 else Decimal(0)
        expense = -self.card_amount if self.card_amount < 0 else Decimal(0)
        rate = None
        if self.txn_currency != self.card_currency and self.txn_amount:
            rate = {
                "rate": float(round(abs(self.card_amount) / abs(self.txn_amount), 6)),
                "from": self.txn_currency,
                "to": self.card_currency,
            }
        return {
            "date": self.date,
            "time": f"{self.time}:00" if self.time and len(self.time) == 5 else self.time,
            "operation": self.operation(),
            "description": self.description,
            "reference": self.reference,
            "auth_code": None,
            "exchange_rate": rate,
            "amount": float(abs(self.txn_amount)),
            "currency": self.txn_currency,
            "income": money(income),
            "expense": money(expense),
        }


@dataclass
class Statement:
    number: str
    generated: str                  # ISO date
    account: str
    contract: str | None
    holder: str | None
    bic: str | None
    period_from: str
    period_to: str
    balance_end: Decimal
    total_in: Decimal | None
    total_out: Decimal | None
    rows: list[Row] = field(default_factory=list)

    @property
    def currency(self) -> str:
        return self.rows[0].card_currency if self.rows else "RUB"


def find_columns(words: list[dict]) -> tuple[list[float], float] | None:
    """Locate the repeated table header and return the six column x-anchors
    plus the y below which table content starts."""
    anchor = next((w for w in words if w["text"] == "description"), None)
    if anchor is None:
        return None
    band = [w for w in words if abs(w["top"] - anchor["top"]) < 14]
    dates = sorted(w["x0"] for w in band if w["text"] == "Date")
    amounts = sorted(w["x0"] for w in band if w["text"] == "Transaction")
    cards = [w["x0"] for w in band if w["text"] == "Card"]
    if len(dates) < 2 or len(amounts) < 3 or not cards:
        return None
    columns = [dates[0], dates[1], amounts[0], amounts[1], amounts[2], cards[0]]
    bottom = max(w["bottom"] for w in band)
    return columns, bottom


def cluster_lines(words: list[dict]) -> list[list[dict]]:
    lines: list[list[dict]] = []
    for word in sorted(words, key=lambda w: w["top"]):
        if lines and word["top"] - lines[-1][-1]["top"] <= LINE_TOLERANCE:
            lines[-1].append(word)
        else:
            lines.append([word])
    return lines


def column_of(word: dict, columns: list[float]) -> int:
    index = 0
    for i, x0 in enumerate(columns):
        if word["x0"] >= x0 - COLUMN_SLACK:
            index = i
    return index


def parse_amount(words: list[dict]) -> tuple[Decimal, str] | None:
    text = " ".join(w["text"] for w in sorted(words, key=lambda w: w["x0"]))
    match = AMOUNT.match(text.strip())
    if not match:
        return None
    sign, value, symbol = match.groups()
    amount = number(value)
    return (-amount if sign == "-" else amount), currency_code(symbol)


class RowBuilder:
    """Accumulates the visual lines of one transaction across page breaks."""

    def __init__(self):
        self.rows: list[Row] = []
        self.pending: dict[int, list[dict]] | None = None

    def line(self, line: list[dict], columns: list[float]) -> None:
        buckets: dict[int, list[dict]] = {}
        for word in line:
            buckets.setdefault(column_of(word, columns), []).append(word)
        starts_row = any(DATE.match(w["text"]) for w in buckets.get(0, []))
        if starts_row:
            self.flush()
            self.pending = buckets
        elif self.pending is not None:
            for key, words in buckets.items():
                self.pending.setdefault(key, []).extend(words)

    def flush(self) -> None:
        if self.pending is None:
            return
        buckets, self.pending = self.pending, None
        date = time = None
        for word in sorted(buckets.get(0, []), key=lambda w: w["top"]):
            if DATE.match(word["text"]):
                date = date or word["text"]
            elif TIME.match(word["text"]):
                time = time or word["text"]
        txn = parse_amount(buckets.get(2, []))
        card_amt = parse_amount(buckets.get(3, []))
        description = " ".join(
            w["text"] for w in sorted(buckets.get(4, []),
                                      key=lambda w: (w["top"], w["x0"])))
        card_words = sorted(buckets.get(5, []), key=lambda w: w["top"])
        card = card_words[0]["text"] if card_words else None
        if date is None or txn is None or card_amt is None:
            raise ValueError(
                f"unparseable transaction row near date={date!r} "
                f"description={description[:40]!r}")
        self.rows.append(Row(
            date=iso_date(date),
            time=time,
            txn_amount=txn[0],
            txn_currency=txn[1],
            card_amount=card_amt[0],
            card_currency=card_amt[1],
            description=re.sub(r"\s+", " ", description).strip(),
            card=None if card in (None, "—", "-") else card,
        ))


def parse_statement(path: Path) -> Statement:
    with pdfplumber.open(path) as pdf:
        full_text = "\n".join(page.extract_text() or "" for page in pdf.pages)
        first_text = pdf.pages[0].extract_text() or ""

        required = {
            "statement number": STATEMENT_NO.search(first_text),
            "period": PERIOD.search(first_text),
            "account number": ACCOUNT.search(first_text),
            "available balance": BALANCE.search(first_text),
        }
        missing = [name for name, match in required.items() if match is None]
        if missing:
            raise ValueError(
                "Not a T-Bank statement (missing: %s)" % ", ".join(missing))

        contract = CONTRACT.search(first_text)
        holder = HOLDER.search(first_text)
        bic = BIC.search(full_text)
        total_in = TOTAL_IN.search(full_text)
        total_out = TOTAL_OUT.search(full_text)

        statement = Statement(
            number=required["statement number"].group(1),
            generated=iso_date(required["statement number"].group(2)),
            account=required["account number"].group(1),
            contract=contract.group(1) if contract else None,
            holder=holder.group(1).strip() if holder else None,
            bic=bic.group(1) if bic else None,
            period_from=iso_date(required["period"].group(1)),
            period_to=iso_date(required["period"].group(2)),
            balance_end=number(required["available balance"].group(1)),
            total_in=number(total_in.group(1)) if total_in else None,
            total_out=number(total_out.group(1)) if total_out else None,
        )

        builder = RowBuilder()
        for page in pdf.pages:
            words = page.extract_words()
            located = find_columns(words)
            if located is None:
                continue                    # totals/signature page
            columns, header_bottom = located
            # the legal footer starts with a « quote and spans all columns
            footer_tops = [w["top"] for w in words if w["text"].startswith("«")]
            cutoff = min(footer_tops) if footer_tops else page.height
            body = [w for w in words
                    if header_bottom + 1 < w["top"] < cutoff - 1]
            for line in cluster_lines(body):
                builder.line(line, columns)
        builder.flush()
        statement.rows = builder.rows

    for index, row in enumerate(statement.rows, start=1):
        row.reference = f"{statement.number}:{index}"
    return statement


def verify(statement: Statement) -> list[str]:
    problems = []
    income = sum((r.card_amount for r in statement.rows if r.card_amount > 0),
                 Decimal(0))
    expense = -sum((r.card_amount for r in statement.rows if r.card_amount < 0),
                   Decimal(0))
    if statement.total_in is None:
        problems.append("MISMATCH: printed Replenishment total not found")
    elif income != statement.total_in:
        problems.append(
            f"MISMATCH: sum of incomes {income} != printed Replenishment "
            f"{statement.total_in}")
    if statement.total_out is None:
        problems.append("MISMATCH: printed Expenses total not found")
    elif expense != statement.total_out:
        problems.append(
            f"MISMATCH: sum of expenses {expense} != printed Expenses "
            f"{statement.total_out}")
    for row in statement.rows:
        if row.card_amount == 0:
            problems.append(
                f"MISMATCH: zero-amount row at {row.date} "
                f"'{row.description[:40]}'")
    return problems


def to_json(statement: Statement, source: str) -> dict:
    def total(*operations: str) -> float:
        return money(-sum((r.card_amount for r in statement.rows
                           if r.card_amount < 0 and r.operation() in operations),
                          Decimal(0)))

    expense_total = statement.total_out if statement.total_out is not None else \
        -sum((r.card_amount for r in statement.rows if r.card_amount < 0), Decimal(0))
    income_total = statement.total_in if statement.total_in is not None else \
        sum((r.card_amount for r in statement.rows if r.card_amount > 0), Decimal(0))
    balance_start = statement.balance_end - income_total + expense_total

    return {
        "source": source,
        "bank": {"name": "T-Bank", "bic": statement.bic},
        "statement": {
            "type": f"Bank Statement №{statement.number}",
            "generated_at": f"{statement.generated}T00:00:00",
            "period_from": statement.period_from,
            "period_to": statement.period_to,
        },
        "holder": {"name": statement.holder, "iin": None,
                   "card_number": None},
        "accounts": [{
            "account": statement.account,
            "currency": statement.currency,
            "summary": {
                "balance_start": money(balance_start),
                "balance_end": money(statement.balance_end),
                "deposits": money(income_total),
                "transfers": total("Transfers"),
                "purchases": total("Payment"),
                "withdrawals": total("Withdrawals"),
                "fees": total("Fee", "Commission"),
                "others": 0.0,
                "amount_in_process": 0.0,
                "balance_total": money(statement.balance_end),
            },
            "transactions": [row.to_json() for row in statement.rows],
        }],
    }


def redact(document: dict) -> dict:
    """Mask the holder name and long digit runs (accounts, contracts, phone
    numbers) keeping the last 4 characters so own-transfer matching works."""
    def mask(match: re.Match) -> str:
        return f"****{match.group(0)[-4:]}"

    document["holder"]["name"] = None
    for account in document["accounts"]:
        account["account"] = f"****{account['account'][-4:]}"
        for row in account["transactions"]:
            row["description"] = LONG_DIGITS.sub(mask, row["description"])
    return document


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert a T-Bank PDF statement to JSON.")
    parser.add_argument("pdf", type=Path, help="path to the PDF statement")
    parser.add_argument("-o", "--output", type=Path,
                        help="write JSON to a file (default: stdout)")
    parser.add_argument("--redact", action="store_true",
                        help="mask the holder name and account numbers")
    parser.add_argument("--indent", type=int, default=2,
                        help="JSON indent (0 for compact)")
    parser.add_argument("--no-verify", action="store_true",
                        help="skip checksum verification")
    args = parser.parse_args(argv)

    if not args.pdf.is_file():
        print(f"File not found: {args.pdf}", file=sys.stderr)
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

    payload = json.dumps(document, ensure_ascii=False,
                         indent=args.indent or None)
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
        print(f"{len(statement.rows)} transactions -> {args.output}",
              file=sys.stderr)
    else:
        print(payload)

    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    if not args.no_verify:
        print("Checksum verification against the statement's totals passed.",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
