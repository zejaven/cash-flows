"""T-Bank (Tinkoff) loan payment schedule PDF -> JSON converter.

    python parsers/schedules/tbank.py График_платежей.pdf [-o out.json]

Portrait A4, one ruled table continued across pages, seven columns: payment
number, due date, total payment, principal part, interest part, monthly fee
for extra services (dash when absent), principal remaining after the payment.
The first months may be interest-only (grace period, principal 0.00). The
table ends with an "Итого:" totals row; header/footer text is ignored.
Amounts use space thousand separators and a dot decimal (comma tolerated).

Verification: per-row principal + interest + extra == total (T-Bank prints
the split exactly); the remaining-balance chain
remaining[i-1] - principal[i] == remaining[i]; column sums against "Итого:".
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path

import pdfplumber

DATE_CELL = re.compile(r"^\d{2}\.\d{2}\.\d{4}$")
INT_CELL = re.compile(r"^\d+$")
TOLERANCE_ROW = 0.01
TOLERANCE_TOTAL = 0.02


def parse_amount(cell: str | None) -> float | None:
    if cell is None:
        return None
    compact = cell.replace("\xa0", "").replace(" ", "").replace("\n", "")
    compact = compact.replace(",", ".")
    if not compact or compact in {"-", "X", "Х"}:
        return None
    try:
        return float(compact)
    except ValueError:
        return None


def parse_date(cell: str) -> str:
    return datetime.strptime(cell.strip(), "%d.%m.%Y").date().isoformat()


def compact(cell: str | None) -> str:
    return re.sub(r"\s+", "", cell or "")


def extract(path: Path) -> tuple[list[dict], dict | None]:
    """Returns (items, totals) where totals is the parsed Итого row or None."""
    items: list[dict] = []
    totals: dict | None = None
    with pdfplumber.open(path) as pdf:
        for page in pdf.pages:
            for table in page.extract_tables():
                for row in table:
                    if not row or len(row) < 7:
                        continue
                    cells = [compact(cell) for cell in row]
                    if cells[0].lower().startswith("итого"):
                        # Итого: | | total | principal | interest | extra |
                        totals = {
                            "total": parse_amount(row[2]),
                            "principal": parse_amount(row[3]),
                            "interest": parse_amount(row[4]),
                        }
                        continue
                    if totals is not None:
                        continue
                    if not INT_CELL.match(cells[0]) or not DATE_CELL.match(cells[1]):
                        continue  # header rows
                    total = parse_amount(row[2])
                    principal = parse_amount(row[3])
                    interest = parse_amount(row[4])
                    extra = parse_amount(row[5]) or 0.0
                    remaining = parse_amount(row[6])
                    if total is None or principal is None or interest is None:
                        continue
                    items.append({
                        "position": int(cells[0]),
                        "due_date": parse_date(cells[1]),
                        "principal": principal,
                        "interest": round(interest + extra, 2),
                        "total": total,
                        "remaining": remaining,
                    })
    return items, totals


def verify(items: list[dict], totals: dict | None) -> list[str]:
    problems: list[str] = []
    for item in items:
        if abs(item["principal"] + item["interest"] - item["total"]) > TOLERANCE_ROW:
            problems.append(
                "MISMATCH row %d: principal %.2f + interest %.2f != total %.2f"
                % (item["position"], item["principal"], item["interest"], item["total"]))
    for prev, cur in zip(items, items[1:]):
        if prev["remaining"] is None or cur["remaining"] is None:
            continue
        if abs(prev["remaining"] - cur["principal"] - cur["remaining"]) > TOLERANCE_ROW:
            problems.append(
                "MISMATCH row %d: remaining %.2f - principal %.2f != remaining %.2f"
                % (cur["position"], prev["remaining"], cur["principal"], cur["remaining"]))
    if totals is not None:
        for key in ("principal", "interest", "total"):
            printed = totals.get(key)
            if printed is None:
                continue
            actual = sum(item[key] for item in items)
            if abs(actual - printed) > TOLERANCE_TOTAL:
                problems.append(
                    "MISMATCH totals %s: sum %.2f != printed %.2f" % (key, actual, printed))
    positions = [item["position"] for item in items]
    if len(set(positions)) != len(positions):
        problems.append("MISMATCH duplicate payment positions")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("file", type=Path)
    parser.add_argument("-o", "--output", type=Path)
    parser.add_argument("--indent", type=int, default=2)
    parser.add_argument("--no-verify", action="store_true")
    args = parser.parse_args()

    try:
        items, totals = extract(args.file)
    except Exception as error:  # noqa: BLE001 - any unreadable input is exit 2
        print(f"Cannot read schedule: {error}", file=sys.stderr)
        return 2
    if not items:
        print("No schedule rows found — unsupported layout?", file=sys.stderr)
        return 2

    if not args.no_verify:
        problems = verify(items, totals)
        if problems:
            for problem in problems:
                print(problem, file=sys.stderr)
            return 1

    result = {"source": args.file.name, "currency": "RUB", "items": items}
    text = json.dumps(result, ensure_ascii=False, indent=args.indent)
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
