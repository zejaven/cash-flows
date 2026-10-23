"""LOCKO-Bank loan payment schedule PDF -> JSON converter.

    python parsers/schedules/locko.py "График платежей.pdf" [-o out.json]

Appendix No. 1 to a consumer-credit agreement ("ГРАФИК ПЛАТЕЖЕЙ"). Portrait
A4, one ruled table continued across pages, six columns: due date, total
payment, principal part, interest part, principal remaining after the
payment, early-repayment amount (remaining + total). The header spans two
rows; the table ends with an "ИТОГО:" row (total, principal, interest).
Amounts have no thousand separators and a decimal comma.

The table prints no payment numbers — positions are 1-based in document
order. A schedule re-issued after payments were made starts at the next
unpaid date, so positions are relative to this document, and its principal
total equals the outstanding principal, not the original loan amount.

Verification: per-row principal + interest == total and
remaining + total == early-repayment amount (LOCKO prints both exactly); the
remaining-balance chain remaining[i-1] - principal[i] == remaining[i]; column
sums against "ИТОГО:".
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
TOLERANCE_ROW = 0.01
TOLERANCE_TOTAL = 0.02


def parse_amount(cell: str | None) -> float | None:
    if cell is None:
        return None
    text = re.sub(r"\s+", "", cell).replace(",", ".")
    try:
        return float(text) if text else None
    except ValueError:
        return None


def parse_date(cell: str) -> str:
    return datetime.strptime(cell.strip(), "%d.%m.%Y").date().isoformat()


def extract(path: Path) -> tuple[list[dict], dict | None]:
    """Returns (items, totals) where totals is the parsed ИТОГО row or None."""
    items: list[dict] = []
    totals: dict | None = None
    with pdfplumber.open(path) as pdf:
        for page in pdf.pages:
            for table in page.extract_tables():
                for row in table:
                    if not row or len(row) < 5:
                        continue
                    first = re.sub(r"\s+", "", row[0] or "")
                    if first.upper().startswith("ИТОГО"):
                        totals = {
                            "total": parse_amount(row[1]),
                            "principal": parse_amount(row[2]),
                            "interest": parse_amount(row[3]),
                        }
                        continue
                    if totals is not None or not DATE_CELL.match(first):
                        continue  # header rows / anything after the totals
                    total = parse_amount(row[1])
                    principal = parse_amount(row[2])
                    interest = parse_amount(row[3])
                    if total is None or principal is None or interest is None:
                        continue
                    items.append({
                        "position": len(items) + 1,
                        "due_date": parse_date(first),
                        "principal": principal,
                        "interest": interest,
                        "total": total,
                        "remaining": parse_amount(row[4]),
                        "_early": parse_amount(row[5]) if len(row) > 5 else None,
                    })
    return items, totals


def verify(items: list[dict], totals: dict | None) -> list[str]:
    problems: list[str] = []
    for item in items:
        if abs(item["principal"] + item["interest"] - item["total"]) > TOLERANCE_ROW:
            problems.append(
                "MISMATCH row %d: principal %.2f + interest %.2f != total %.2f"
                % (item["position"], item["principal"], item["interest"], item["total"]))
        if item["remaining"] is not None and item["_early"] is not None \
                and abs(item["remaining"] + item["total"] - item["_early"]) > TOLERANCE_ROW:
            problems.append(
                "MISMATCH row %d: remaining %.2f + total %.2f != early repayment %.2f"
                % (item["position"], item["remaining"], item["total"], item["_early"]))
    for prev, cur in zip(items, items[1:]):
        if prev["remaining"] is None or cur["remaining"] is None:
            continue
        if abs(prev["remaining"] - cur["principal"] - cur["remaining"]) > TOLERANCE_ROW:
            problems.append(
                "MISMATCH row %d: remaining %.2f - principal %.2f != remaining %.2f"
                % (cur["position"], prev["remaining"], cur["principal"], cur["remaining"]))
    if totals is None:
        problems.append("MISMATCH totals row (ИТОГО) not found")
    else:
        for key in ("principal", "interest", "total"):
            printed = totals.get(key)
            if printed is None:
                problems.append("MISMATCH totals %s not printed" % key)
                continue
            actual = sum(item[key] for item in items)
            if abs(actual - printed) > TOLERANCE_TOTAL:
                problems.append(
                    "MISMATCH totals %s: sum %.2f != printed %.2f" % (key, actual, printed))
    if items and items[-1]["remaining"] not in (None, 0.0):
        problems.append("MISMATCH last row leaves remaining %.2f" % items[-1]["remaining"])
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

    for item in items:
        del item["_early"]
    result = {"source": args.file.name, "currency": "RUB", "items": items}
    text = json.dumps(result, ensure_ascii=False, indent=args.indent)
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
