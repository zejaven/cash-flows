# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Personal multi-currency cash flow tracker (single user). Java 21 · Spring Boot 3.4 · PostgreSQL · Liquibase · React 18 + TypeScript + Vite. One deployable jar: `frontend-maven-plugin` builds the SPA during `mvn package` and copies `frontend/dist` into `classpath:/static`.

## Commands

```bash
# Local development (three pieces)
docker compose up -d postgres                          # DB on localhost:5434
mvn spring-boot:run -Dspring-boot.run.profiles=local   # backend on :8080
cd frontend && npm run dev                             # UI on :5173, /api proxied to :8080

# Full stack in Docker: app on :8081, postgres on :5434
docker compose up --build

# Tests
mvn verify                                             # unit tests only
mvn verify -Dexcluded.test.groups=                     # + integration tests
mvn test -Dtest=RouteCalculatorTest                    # single class
mvn test -Dtest=RouteCalculatorTest#methodName         # single method

# Frontend type-check + build (no lint or frontend tests configured)
cd frontend && npm run build

# Run a statement parser standalone (deps: pip install -r requirements.txt; .venv exists)
python parsers/bnz.py <statement.pdf> [-o out.json]
```

IntelliJ IDEA run configurations for the same local setup live in `.run/` (Postgres compose, Backend `local` profile, Frontend vite dev, and a "Full stack (local)" compound of the last two).

Integration tests are `@Tag("integration")`, excluded by the `excluded.test.groups` Maven property. They run against the compose postgres on port 5434 using a dedicated `cash_flows_test` database — create it once:

```bash
docker exec cash-flows-postgres-1 psql -U cashflows -d cash_flows \
  -c "CREATE DATABASE cash_flows_test OWNER cashflows"
```

Testcontainers is deliberately not used (docker-java can't talk to Docker Desktop 29.x).

Secrets: `config/secrets.yml` (git-ignored, template `config/secrets.yml.example`), pulled in via `spring.config.import`. Supplies DB credentials and the single app user (`app.username` / `app.password`). Docker takes the same values from env vars (`DB_USERNAME`, `DB_PASSWORD`, `APP_USERNAME`, `APP_PASSWORD`).

## Architecture

Backend layering under `org.zeveon.cashflows`: `controller` → `service` (interface in `service/`, implementation in `service/impl/`) → `repository`, with JPA entities in `entity/`, request/response DTOs in `model/dto/` (multi-record files like `RecurringDtos`), MapStruct mappers in `mapper/` (shared `GlobalMapperConfig`), Lombok throughout. Errors: throw `NotFoundException` / `ConflictException`; `RestExceptionHandler` maps them to HTTP statuses.

**Security** (`config/SecurityConfig`): one in-memory user from secrets; form login at `/api/login` returns 200/401 instead of redirects; cookie CSRF — readable `XSRF-TOKEN` cookie echoed back in the `X-XSRF-TOKEN` header, BREACH/Xor handler disabled so the plain token matches. `/api/**` is authenticated; everything else is the SPA shell and static assets, permitted. The Vite dev proxy has `changeOrigin: false` on purpose — session + cookie CSRF depend on Origin/Host being preserved.

**Database**: Liquibase changelogs in `src/main/resources/db/changelog/`, one file per change named `YYYY-MM-DD-HH-mm-ss_description.xml`, all included from `changelog-master.xml`. App tables live in the `cashflow` schema; Liquibase bookkeeping in `public`. `generate-ddl` is off — every schema change needs a new changelog file.

**FX conversion**: `fx/FxFacade` fetches daily USD-base rates from open.er-api.com via Retrofit; fail-soft (empty `Optional`, failures not cached, UI falls back to original currencies); cached until `UpdateFxCacheJob`'s daily evict; snapshots persisted as `FxRateSnapshot`. Multi-hop conversion routes (`ConversionRoute`/`ConversionStep` entities, `conversion/RouteCalculator`) model realistic transfer chains between currencies.

**Statement import** (`statement/` + `service/impl/ImportExecutor`): bank statements are parsed by per-bank Python scripts in `parsers/` invoked as subprocesses (`ImportParserProperties`: python command, scripts dir, timeout). All scripts emit the same JSON contract (`parsers/CONTRACT.md`) and verify themselves (exit 1 on mismatch; formats without printed totals — e.g. ByBit CSV — verify structurally). The input format (PDF/CSV/zip) is the parser's business — the backend only ships the file. The parser registry is a naming convention over the scripts dir: `<stem>.py` ⇄ parser id `<stem>` (`StatementParserServiceImpl`, listed via `GET /api/parsers`); a bank is bound to a parser id in Settings. Adding a bank = drop a script in `parsers/` — no code changes. Import flow: parse → preview held in `PendingImportHolder` → `StatementTransformer` + `CategoryRuleMatcher` (auto-categorization rules) → `ImportExecutor` commits, recorded as an `ImportRun`.

**AI parser generator** (`generator/`, local-only — disabled in Docker): the Import page can generate a new parser from one or more sample PDFs (the agent must handle every sample; backend verification re-runs the script over each) by running a coding-agent CLI (`claude` / `codex`, provider+model persisted in `generator_settings`, edited in Settings) in the repo root with write access. `AgentCliRunner` resolves Windows npm shims, streams stream-json into normalized events, and kills the whole process tree on timeout/cancel; `ParserGeneratorServiceImpl` holds a single in-memory task with an SSE live log (replay + reattach), then verifies the generated script by re-running it via `StatementParserService` before declaring success. Prompt template: `prompts/generate-parser.md`; staging dir `.generator-tmp/` (git-ignored). Config: `import.generator.*`.

**Recurring / planned / forecast**: `recurring/ScheduleCalculator` expands recurring rules into occurrences; `RecurringAmountCalculator` supports averaging strategies (`AveragingProfile` over historic category spend); `ForecastService` projects balances from recurring + planned transactions.

**Frontend** (`frontend/src`): `api.ts` is the single fetch wrapper (auth/CSRF handling); `types.ts` mirrors backend DTOs; one component per page in `pages/`, shared contexts in `context/` (Settings, Theme, Toast). PWA manifest in `public/`.

## Conventions

- Class-level Javadoc explains the "why" and references requirement IDs (FR-x.y / NFR-x) from `docs/requirements.md`; classes carry `@author Stanislav Vafin`.
- `docs/` is git-ignored (contains personal data and real bank statements) but exists locally: requirements, per-bank statement structure notes (e.g. `docs/Alatau City Bank/bank-statement-structure.md` defines the parser JSON contract), UI mockups in `docs/design/`. Some docs are in Russian.
- `statement.json` at the repo root is parser output with personal data — git-ignored, don't commit or paste its contents.
