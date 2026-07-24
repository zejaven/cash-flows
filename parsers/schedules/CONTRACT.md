# Loan payment-schedule parser contract

Every script in this directory parses one bank's loan payment schedule (the
amortization table issued with a credit agreement) and emits the same JSON.
Discovery works like statement parsers: script `<stem>.py` is exposed as
schedule-parser id `<stem>` (stem: `^[a-z][a-z0-9_-]*$`), no registration step.

The input format is the parser's business — the backend only ships the
uploaded file. Reference implementations: `rshb.py` and `tbank.py` (portrait
PDF, one ruled table continued across pages, pdfplumber `extract_tables`;
column orders differ per bank). Grace-period rows (interest-only, principal
0.00 — e.g. T-Bank's first months) are emitted as-is.

## CLI

```
python parsers/schedules/<stem>.py <schedule-file> [-o OUT.json] [--indent N] [--no-verify]
```

- Positional argument — path to the schedule file. `-o/--output` writes JSON to
  a file; without it JSON goes to **stdout** (nothing else may be printed to
  stdout). `--indent` (default 2). `--no-verify` skips verification.
- Exit codes: **0** success · **1** verification failed — print one line per
  problem to **stderr**, each containing the word `MISMATCH` · **2**
  unreadable/unsupported input.
- Everything is UTF-8 (`PYTHONIOENCODING=utf-8` is set by the backend).

## Output JSON (all keys snake_case)

```json
{
  "source": "<file name>",
  "currency": "RUB",
  "items": [
    {
      "position": 1,
      "due_date": "2021-08-30",
      "principal": 0.0,
      "interest": 7796.71,
      "total": 7796.71,
      "remaining": 3060000.0
    }
  ]
}
```

- `position` — the payment number as printed (1-based, unique).
- `due_date` — ISO date; keep the bank's business-day-shifted date as printed,
  do not normalize it back to the contractual day.
- `principal` + `interest` = `total` for every row.
- `remaining` — principal outstanding **after** the payment (null if the bank
  does not print it).

## Verification (exit 1 on any failure)

- Chain: `remaining[i-1] − principal[i] == remaining[i]` (±0.01) — catches
  mis-parsed rows in long tables.
- When the document prints a totals row: column sums must match it (±0.02).
- Per row: `principal + interest ≈ total` with a bank-specific tolerance —
  RSHB prints business-day-shift interest into the total column only, so the
  split is off by a few rubles on shifted dates and only gross deviations fail.
