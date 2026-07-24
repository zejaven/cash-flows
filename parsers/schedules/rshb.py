"""RSHB (Rosselkhozbank) loan payment schedule PDF -> JSON converter.

    python parsers/schedules/rshb.py PaymentSchedule.pdf [-o out.json]

Portrait A4, "График платежей по кредитному договору" (Приложение к кредитному
договору): one ruled six-column table continued across pages — payment number,
due date (business-day-shifted as printed), principal part, interest part,
total payment, principal remaining after the payment. The table ends with an
"Итого" totals row; the following sections (extra services, grand total) are
ignored. Amounts are printed with a dot decimal separator, but a comma variant
and space thousand separators are tolerated.

Verification: the remaining-balance chain remaining[i-1] - principal[i] ==
remaining[i] and column sums against the printed "Итого" row are exact. The
per-row split check principal + interest == total is deliberately loose: on
business-day-shifted dates RSHB prints a few extra rubles of interest into the
total column without reflecting them in the interest column (rows 4, 13, 39,
47 of the reference document), so only gross deviations fail.
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
# principal + interest vs total: RSHB bakes day-shift interest into the total
# column only, so the split is off by a few rubles on shifted dates
TOLERANCE_SPLIT = 10.0
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
                    if not row or len(row) < 5:
                        continue
                    cells = [compact(cell) for cell in row]
                    if compact(row[0]).lower() == "итого":
                        # Итого | X | principal | interest | total | X
                        totals = {
                            "principal": parse_amount(row[2]),
                            "interest": parse_amount(row[3]),
                            "total": parse_amount(row[4]),
                        }
                        continue
                    if totals is not None:
                        continue  # sections after the schedule table are ignored
                    if not INT_CELL.match(cells[0]) or not DATE_CELL.match(cells[1]):
                        continue  # header / section rows
                    principal = parse_amount(row[2])
                    interest = parse_amount(row[3])
                    total = parse_amount(row[4])
                    remaining = parse_amount(row[5]) if len(row) > 5 else None
                    if principal is None or interest is None or total is None:
                        continue
                    items.append({
                        "position": int(cells[0]),
                        "due_date": parse_date(cells[1]),
                        "principal": principal,
                        "interest": interest,
                        "total": total,
                        "remaining": remaining,
                    })
    return items, totals


def verify(items: list[dict], totals: dict | None) -> list[str]:
    problems: list[str] = []
    for item in items:
        if abs(item["principal"] + item["interest"] - item["total"]) > TOLERANCE_SPLIT:
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
