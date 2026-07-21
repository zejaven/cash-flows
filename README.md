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

## Statement parser

`parse_statement.py` converts an Alatau City Bank PDF statement to JSON
(`pip install -r requirements.txt`, see
[docs/bank-statement-structure.md](docs/bank-statement-structure.md)).
Integration into the app import flow is stage 2 of the roadmap
(requirements §5).
