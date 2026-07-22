# Generate a bank statement parser

You are working inside the **cash-flows** repository. Your mission: create
**exactly one new file** — `parsers/{{script_name}}` — a statement parser for
**{{bank_name}}**. When the backend sees that file, the parser becomes
available as id `{{parser_id}}`; no other change is needed.

**Do not modify anything else.** No Java, no frontend, no config, no existing
parsers, no git commands. The only files you may create or change are
`parsers/{{script_name}}` and scratch files inside `{{work_dir}}`.

## 1. Learn the contract

Read `parsers/CONTRACT.md` — the output JSON schema, the CLI flags, the exit
codes and the checksum self-verification are non-negotiable. Mirror the
structure and style of the reference parsers `parsers/alatau.py` and
`parsers/bnz.py`: argparse-based `main(argv) -> int`, pdfplumber, `--redact`
support, a module docstring (in English) describing the layout quirks you
discovered.

## 2. Explore the sample statements

The sample statements are:

{{pdf_paths}}

Explore the structure of **every** sample — page text, tables, word
coordinates via pdfplumber — using small throwaway snippets. Different
statements from the same bank often differ (period length, number of pages,
multi-currency sections, empty accounts, pending blocks): the samples exist
precisely to expose those variants, so compare them and handle everything you
see. Write any scratch output **only** under `{{work_dir}}`. Python and
pdfplumber are already installed (`python` on PATH).

Pay attention to: how transactions are laid out (table vs positioned words),
which summary totals the statement prints (you must verify against them), the
bank's own operation-type wording (map it onto the fixed vocabulary from the
contract), date/number formats (thousand separators, decimal commas), and
whether rows carry a unique reference (synthesize one if not).

## 3. Self-validation loop (required)

Iterate until the parser is correct for **every** sample:

```
python parsers/{{script_name}} "<sample.pdf>" -o {{work_dir}}/out-<n>.json
```

Do not consider the task done until, **for each sample statement**, all of
these hold:

- exit code is 0;
- the JSON contains every key required by the contract with correct types;
- every transaction's `operation` is from the fixed vocabulary;
- the checksum self-verification passes against that statement's own totals.

A parser that handles some samples but not others is not done. If one
document's totals genuinely cannot be reconciled with its rows, stop and
explain why instead of weakening or skipping verification.

## 4. Privacy (hard rule)

Never copy concrete values from the statements — names, account numbers,
IBANs, card numbers, references, merchant names, amounts, dates — into the
parser source, its comments, its docstring, or any file outside `{{work_dir}}`.
Describe the layout with structural patterns and regexes only.

## 5. Finish

End with a short summary: the layout assumptions you made, differences you
observed between the samples and how the parser handles them, the checksum
strategy, and any known unsupported cases (e.g. statement variants you could
not observe in the samples).
