# Statement parser contract

Every script in this directory parses one bank's PDF statement and emits the
same JSON. The backend discovers parsers by convention: script `<stem>.py` is
exposed as parser id `<stem>-pdf` (stem: `^[a-z][a-z0-9_-]*$`). No registration
step exists — the file's presence is the registration.

Reference implementations: `alatau.py` (table-based, multi-currency),
`bnz.py` (word-coordinate reconstruction, synthesized references).

## CLI

```
python parsers/<stem>.py <statement.pdf> [-o OUT.json] [--redact] [--indent N] [--no-verify]
```

- Positional `pdf` — path to the statement. `-o/--output` writes the JSON to a
  file; without it the JSON goes to **stdout** (nothing else may be printed to
  stdout). `--redact` masks personal fields (holder name, account numbers) in
  the output. `--indent` (default 2). `--no-verify` skips checksum verification.
- Exit codes: **0** success · **1** checksum verification failed — print one
  line per problem to **stderr**, each containing the word `MISMATCH` · **2**
  unreadable/unsupported input.
- Everything is UTF-8. Do not rely on the console code page (the backend sets
  `PYTHONIOENCODING=utf-8`).

## Output JSON (all keys snake_case)

```json
{
  "source": "<pdf file name>",
  "bank": { "name": "<bank name>", "bic": "<BIC/SWIFT or null>" },
  "statement": {
    "type": "<statement kind, free text>",
    "generated_at": "YYYY-MM-DDTHH:MM:SS or null",
    "period_from": "YYYY-MM-DD",
    "period_to": "YYYY-MM-DD"
  },
  "accounts": [
    {
      "account": "<full account number / IBAN>",
      "currency": "<ISO 4217, e.g. KZT>",
      "summary": {
        "balance_start": 0.0,
        "balance_end": 0.0,
        "deposits": 0.0,
        "transfers": 0.0,
        "purchases": 0.0,
        "withdrawals": 0.0,
        "fees": 0.0,
        "others": 0.0,
        "amount_in_process": 0.0,
        "balance_total": 0.0
      },
      "transactions": [
        {
          "date": "YYYY-MM-DD",
          "time": "HH:MM:SS or null",
          "operation": "<see vocabulary>",
          "description": "<free text>",
          "reference": "<unique within the statement — see notes>",
          "auth_code": "<or null>",
          "exchange_rate": { "rate": 0.0, "from": "USD", "to": "KZT" },
          "amount": 0.0,
          "currency": "<transaction currency>",
          "income": 0.0,
          "expense": 0.0
        }
      ]
    }
  ]
}
```

Rules:

- Numbers are JSON numbers, never strings. Amounts are positive; direction is
  expressed by `income` vs `expense` (exactly one of them non-zero per row;
  the other is `0` or `null`).
- `operation` is a **fixed vocabulary** — map the bank's own type codes onto:
  `Payment | Replenishment | Commission | Withdrawals | Transfers | Fee`.
  `Withdrawals` specifically means cash withdrawal (ATM) — the importer builds
  cash-account suggestions from it. `Replenishment` is incoming money.
- `exchange_rate` is `null` when the transaction is in the account currency.
- `reference` must be unique within the statement (it feeds import
  deduplication). If the bank prints no reference, synthesize a stable one,
  e.g. `"<statement no>:<row no>"` (see `bnz.py`).
- Unknown/extra keys are ignored by the backend; missing summary fields that
  the statement genuinely lacks may be `null` or `0`.

## Mandatory self-verification

Unless `--no-verify` is passed, the script must recompute totals from the
parsed rows and compare them with the statement's **own printed figures**
(tolerance 0.01), e.g.:

- sum of `income` per account vs the printed deposits/credits total;
- `balance_start + income − expense` vs the printed `balance_end`;
- a per-row running balance vs a printed balance column, when one exists.

Any discrepancy: print a `MISMATCH: …` line to stderr per problem and exit 1.
This is the guarantee that a layout change or a parsing bug fails loudly
instead of importing wrong numbers.

## Dependencies

Python 3.11+, `pdfplumber` (see `requirements.txt`) and the standard library —
nothing else. Prefer table/word-coordinate extraction over flat text: statement
layouts break flat-text parsing (see the docstrings of both reference parsers).
