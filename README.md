# Cash Flows

Personal multi-currency cash flow tracker: accounts, manual transactions
(expense / income / transfer), categories, display-currency conversion with
daily FX snapshots. Replaces a Google Sheets workbook — see
[docs/requirements.md](docs/requirements.md) for the full requirement set and
[docs/design/](docs/design/) for the UI mockups.

**Stack**: Java 21 · Spring Boot 3.4 · PostgreSQL · Liquibase · React 18 + TypeScript + Vite.
One deployable jar: the SPA is built by `frontend-maven-plugin` and served from
`classpath:/static`.

## Run (Docker)

```bash
cp config/secrets.yml.example config/secrets.yml   # edit credentials
docker compose up --build
# app: http://localhost:8081  (postgres exposed on 5434)
```

Secrets in Docker come from environment variables (`.env` or shell):
`DB_USERNAME`, `DB_PASSWORD`, `APP_USERNAME`, `APP_PASSWORD`.

## Develop

```bash
docker compose up -d postgres                       # DB on localhost:5434
mvn spring-boot:run -Dspring-boot.run.profiles=local   # backend on :8080
cd frontend && npm run dev                          # UI on :5173, /api proxied to :8080
```

Local secrets: `config/secrets.yml` (git-ignored, template `config/secrets.yml.example`).

## Test

```bash
mvn verify                          # unit tests; integration excluded by default
mvn verify -Dexcluded.test.groups=  # + integration tests (need the compose postgres up)
```

The integration test uses a dedicated `cash_flows_test` database in the compose
postgres. Create it once:

```bash
docker exec cash-flows-postgres-1 psql -U cashflows -d cash_flows \
  -c "CREATE DATABASE cash_flows_test OWNER cashflows"
```

## Statement parsers

Bank statement parsers live in `parsers/`, one script per bank, all emitting
the same JSON contract (see
[bank-statement-structure.md](docs/Alatau%20City%20Bank/bank-statement-structure.md))
and verifying the statement's own checksums (exit 1 on mismatch):

| Parser id (bound to a bank in Settings) | Script | Format |
|---|---|---|
| `alatau-pdf` | `parsers/alatau.py` | Alatau City Bank card statement (multi-currency) |
| `bnz-pdf` | `parsers/bnz.py` | BNZ YouMoney statement (NZD) |

Run standalone: `python parsers/bnz.py <statement.pdf> [-o out.json]`
(`pip install -r requirements.txt`). The app invokes them as subprocesses;
new banks are added by dropping a script here and registering its id in
`StatementParserServiceImpl.REGISTRY`.
