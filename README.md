# ReconFlow

A payment reconciliation and exception resolution platform. ReconFlow compares a business's internal payment ledger against payment gateway settlements, surfaces the discrepancies, and tracks how each one was investigated and resolved.

> **Project status: reliability and performance done (M9).** M9 proved the system survives what goes wrong and measured how fast it is: crash-at-any-point tests whose final state equals a clean run, in-process retries of transient database failures, a real `SIGKILL`/`SIGTERM` of the worker mid-import, separate liveness and readiness probes, Prometheus metrics on an internal port with a Grafana dashboard and alerts, archival of months older than 13, a timed restore drill, and benchmarks (50k × 50k reconciled in 6.5 s; read p95 21 ms under 100 users; a 500k import in 14 s under `-Xmx256m`) recorded in [docs/benchmarks.md](docs/benchmarks.md). Kafka was measured unnecessary ([ADR 0014](docs/adr/0014-no-kafka-in-v1.md)). Earlier: reporting (M8). On top of M2–M6 (profiles, error model, in-house JWT authentication, tenant resolution with PostgreSQL row-level security, a permission matrix, an append-only audit log, CSV ingestion through a PostgreSQL job queue, and a set-based matching engine that creates exception cases deduplicated by a deterministic discrepancy key): an exception workflow where investigators list, filter and open cases with both source records side by side, assign them, add append-only notes, and resolve them with a resolution code, and org admins reopen them with a reason. Every status change goes through one state machine that writes status history and an audit event in the same transaction; every update is guarded by optimistic locking (`ETag`/`If-Match`, `428` when missing, `409` when stale, proven by a two-writer race test); and admins can read any entity's audit timeline. Reporting (M8): one call answers "which match, which don't, why, who resolved them" for a run or a date range (overlapping runs never double count a day), plus import quality, exception ageing and a CSV export of a run's exceptions that streams small runs and hands large ones to a background job, escaped against spreadsheet formula injection; every report query's plan is checked at volume in the build. See [Roadmap](#roadmap).

---

## The problem

A single customer payment can appear in three systems that do not always agree:

- **Internal ledger** — the business records the order as paid.
- **Payment gateway** — the provider records that it processed the payment.
- **Bank / settlement account** — the money that actually arrived, possibly net of fees or on a later date.

At scale, finance teams compare these sources in spreadsheets. ReconFlow replaces that with a deterministic, auditable matching process.

### Worked example

| Transaction | Internal ledger | Gateway file   | Result    |
| ----------- | --------------- | -------------- | --------- |
| TXN-101     | 1,000.00        | 1,000.00       | Matched   |
| TXN-102     | 500.00          | Missing        | Unmatched |
| TXN-103     | 750.00          | 700.00         | Mismatch  |
| TXN-104     | 250.00          | 250.00 ×2      | Duplicate |
| TXN-105     | 300.00          | 300.00         | Matched   |

*Illustrative data. Real matching also considers transaction type, currency, settlement date, fees, and provider file formats.*

ReconFlow answers four questions:

1. Which transactions match?
2. Which are missing, duplicated, or inconsistent?
3. Why did each discrepancy occur?
4. Who investigated it, and how was it resolved?

---

## Architecture

A **modular monolith**: one Spring Boot application with strictly separated modules, backed by PostgreSQL. This keeps transaction boundaries and business rules coherent while avoiding distributed-systems overhead before the domain logic is proven.

```
Browser / API client
        │
        ▼
┌─────────────────────────────────────────────┐
│ Spring Boot REST API                        │
│ auth · authorization · request validation   │
├─────────────────────────────────────────────┤
│ Ingestion    │ Ledger     │ Reconciliation   │
│ files/imports│ normalized │ matching engine  │
│              │ records    │                  │
├─────────────────────────────────────────────┤
│ Exceptions   │ Reporting & audit            │
│ workflow     │ history · accountability     │
└─────────────────────────────────────────────┘
        │
        ▼
   PostgreSQL
   durable records · constraints · transactional consistency

  Background jobs: PostgreSQL job table + worker processes (ADR 0004)
  Later, only if measured: Kafka
```

### Processing pipeline

| Stage | Responsibility |
| ----- | -------------- |
| 01 Ingestion | Import internal transactions and gateway/bank settlement files |
| 02 Validation & normalization | Validate the file, standardize fields, detect duplicate imports |
| 03 Reconciliation | Compare records using deterministic matching rules |
| 04 Exception management | Create cases for missing, duplicated, and mismatched records |
| 05 Investigation & resolution | Assign, investigate, resolve with reason and audit trail |
| 06 Reporting & audit | Reconciliation results, financial totals, action history |

v1 accepts **manual CSV uploads**. Scheduled imports, provider APIs, and event-driven processing come later.

---

## Domain model

| Entity | Purpose |
| ------ | ------- |
| `Organization` | Tenant boundary. Every business-owned row carries `organizationId`. |
| `ImportBatch` | An uploaded CSV: source type, processing status, row counts, stored file reference. |
| `TransactionRecord` | A normalized transaction from either source, preserving original values. |
| `ReconciliationRun` | One matching execution over a defined period and source pair, with summary counts. |
| `ReconciliationMatch` | Persistent link between a matched internal and gateway record. |
| `ExceptionCase` | A discrepancy requiring review: type, status, assignee, resolution. |
| `AuditEvent` | Append-only record of important actions and state changes. |

Monetary values are `BigDecimal` in Java and `NUMERIC` in PostgreSQL — never `double` or `float`. Decimal precision, rounding, and scale are treated as part of the schema contract.

### State machines

| Entity | States |
| ------ | ------ |
| Import batch | `RECEIVED → PROCESSING → COMPLETED` (or `COMPLETED_WITH_ERRORS`, `FAILED`) |
| Reconciliation run | `PENDING → RUNNING → COMPLETED` (or `FAILED`) |
| Exception case | `OPEN → IN_REVIEW → RESOLVED` (reopen retains prior resolution history) |

Valid transitions, required reasons, and authorization rules are defined in [reconciliation-rules.md §7](docs/reconciliation-rules.md#7-state-machines).

---

## Reconciliation rules (v1 scope)

Deterministic one-to-one matching on payment transactions:

1. **Normalize** — standardize references, currency codes, timestamps, and statuses while retaining the raw imported values.
2. **Exact reference match** — same normalized reference, compatible currency, matching transaction type.
3. **Amount comparison** — matching references with differing amounts produce an `AMOUNT_MISMATCH`, never a silent match.
4. **Unmatched detection** — a record present in only one source becomes an unmatched exception.
5. **Duplicate detection** — repeated records within a file and repeated imports are flagged, not silently deduplicated.
6. **Persist results** — matches, exceptions, and run summaries are written atomically.

Explicitly **out of scope for v1**: one-to-many matching for aggregated settlements, refunds/chargebacks/fees as distinct flows, cross-currency matching, configurable settlement windows, fuzzy or probabilistic matching. The model leaves room for these; the engine does not attempt them yet.

---

## Tech stack

| Concern | Choice | Notes |
| ------- | ------ | ----- |
| Language | Java 25 | LTS |
| Framework | Spring Boot 4.1.2 | Web MVC, validation, data JPA, security (OAuth2 resource server, Nimbus JWT), actuator; Spring Modulith 2.1 for module boundaries |
| Database | PostgreSQL | `NUMERIC` for money, FK + unique constraints, transactional writes |
| Migrations | Flyway | V1–V4 applied on startup (V4: row-level security); applied migrations are never edited |
| Build | Maven (wrapper) | `./mvnw`, no system Maven required |
| Testing | JUnit + Testcontainers | Unit tests (`*Test`) need no Docker; integration tests (`*IT`) start their own PostgreSQL 16 |
| Quality gates | Spotless, JaCoCo, GitHub Actions | Palantir Java Format check, coverage reports (no threshold yet), `./mvnw verify` on every PR |
| Async | PostgreSQL job table | Worker processes claim jobs with `SKIP LOCKED` ([ADR 0004](docs/adr/0004-postgresql-job-queue.md)); Kafka deliberately deferred |
| Cache | Redis | Deliberately deferred; only if a concrete need appears |
| Local infra | Docker Compose | PostgreSQL 16 and MinIO (S3-compatible), see `compose.yaml` |

---

## Getting started

### Prerequisites

- **Java 25**: verify with `java -version`
- No system Maven needed; use the bundled wrapper (`./mvnw`, executable)
- **Docker** with Compose (Docker Desktop, OrbStack or Colima). It runs PostgreSQL 16 and MinIO
  for local development and the Testcontainers database for integration tests. No locally
  installed PostgreSQL is needed.

> **Colima.** Docker and Testcontainers look for `/var/run/docker.sock`, which Colima doesn't
> create. Export these (e.g. in your shell profile) before `docker compose` or `./mvnw verify`:
>
> ```bash
> export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
> export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock   # the path inside the Colima VM
> ```
>
> With Homebrew's standalone `docker-compose`, either run `docker-compose ...` or link it as a
> CLI plugin so `docker compose` works:
> `mkdir -p ~/.docker/cli-plugins && ln -sfn "$(brew --prefix)/opt/docker-compose/bin/docker-compose" ~/.docker/cli-plugins/docker-compose`.

### Configure

Credentials live in a gitignored `.env` in the project root, read by both Docker Compose and the
`local` profile:

```bash
cp .env.example .env
# optionally change the passwords (DB_*, S3_SECRET_KEY)
```

The `local` profile imports it with `spring.config.import: optional:file:.env[.properties]`. Real
environment variables take precedence over the file. No other profile reads `.env`: tests get
their database from Testcontainers, and `prod` takes its database settings from the environment
only and refuses to start, naming the variable, if one is missing.

#### Database roles (M10.7)

Three PostgreSQL roles, so row-level security and the append-only audit trail actually hold:

| Role | Env vars | Used by | Can |
|---|---|---|---|
| superuser | `DB_USER`, `DB_PASSWORD` | humans, emergencies, `scripts/*.sh` | everything |
| `reconflow_migrator` | `DB_MIGRATOR_USER`, `DB_MIGRATOR_PASSWORD` | Flyway | owns the schema (DDL) |
| `reconflow_app` | `DB_APP_USER`, `DB_APP_PASSWORD` | the application | read and write data, nothing else; RLS applies |

`compose.yaml` creates the two roles when the PostgreSQL volume is first initialised
(`infra/db/initdb/10-reconflow-roles.sh` runs `infra/db/bootstrap-roles.sql`). A volume created
before M10.7 is never re-initialised; either recreate it (`docker compose down -v`, local data only)
or run the script once by hand:

```bash
docker compose exec -T postgres psql -U "$DB_USER" -d paymentreconflow \
  -v migrator_pw="$DB_MIGRATOR_PASSWORD" -v app_pw="$DB_APP_PASSWORD" -f - < infra/db/bootstrap-roles.sql
```

It also hands every existing object to `reconflow_migrator`. Any other environment runs the same
script once, as a superuser, before the first deploy.

### Start the dependencies

```bash
docker compose up -d
docker compose ps        # postgres and minio "healthy"; minio-init exited (0) after creating the bucket
```

PostgreSQL listens on `localhost:5432` (change `DB_PORT` and `DB_URL` if that's taken), the MinIO
S3 API on `localhost:9000` and its console on `http://localhost:9001` (log in with `S3_ACCESS_KEY` /
`S3_SECRET_KEY`; bucket `reconflow-imports`). Data persists in named volumes;
`docker compose down -v` wipes it.

### Run

```bash
SPRING_PROFILES_ACTIVE=local,api,worker ./mvnw spring-boot:run
```

Flyway applies `V1`–`V9` on first startup (`V7`/`V8` are M8's report support, `V9` M9.16's archive manifest) (`V5` is M6's engine support: summary columns, `exception_case_run`, `uuid_generate_v7()`, the reference index and the one-active-run-per-scope index; `V6` makes exception notes and status history append-only with triggers). Hibernate runs in `validate` mode, so a mismatch between
the entities and the schema fails the boot rather than silently altering it.

| Profile | Kind | Effect |
|---|---|---|
| `local` | Environment | Reads `.env`, readable logs with the request ID, DEBUG SQL, full health details |
| `prod` | Environment | Secrets from environment variables only, JSON (ECS) logs |
| `test` | Environment | Used by the tests (Testcontainers); lives in `src/test/resources` |
| `api` | Role | Serves HTTP traffic |
| `worker` | Role | Turns on the job worker (`reconflow.jobs.worker.enabled`) and scheduled jobs (lease sweeper with a startup sweep, orphaned-work check, job retention, metric gauges, partition maintenance at startup and 02:15 UTC, monthly archival, orphan-file sweeper) |

There is deliberately no default profile. Locally one process runs both roles.

Actuator lives on the management port 8081 in `local` and `prod` (M9.10): probes at
`/actuator/health/liveness` and `/readiness`, metrics at `/actuator/prometheus`, which the public
port refuses.

```bash
curl -s localhost:8081/actuator/health        # {"status":"UP",...}
curl -si localhost:8080/api/v1/members        # 401, application/problem+json, X-Request-Id
```

Errors are RFC 9457 Problem Details with `code` and `requestId`
([api-design.md §3](docs/api-design.md#3-error-model)).

### Log in (local)

The `local` profile seeds demo data on first start (once; a second start seeds nothing) and
generates a fresh JWT signing key at every start, so tokens die on restart. Every seeded user's
password is `reconflow-local-demo` (override with `RECONFLOW_SEED_PASSWORD`).

| Account | Organization(s) and role |
|---|---|
| `admin@acme.test`, `investigator@acme.test`, `uploader@acme.test`, `viewer@acme.test` | Acme Payments (`0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0e`), one role each |
| `admin@globex.test`, `investigator@globex.test`, `uploader@globex.test`, `viewer@globex.test` | Globex Retail (`0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0f`), one role each |
| `consultant@both.test` | Investigator at Acme, viewer at Globex: must send `X-Organization-Id` |
| `platform@reconflow.test` | Platform admin: manages organizations, sees no tenant data |

```bash
TOKEN=$(curl -s -c cookies.txt localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@acme.test","password":"reconflow-local-demo"}' | jq -r .accessToken)
curl -s localhost:8080/api/v1/members -H "Authorization: Bearer $TOKEN" | jq
curl -s -b cookies.txt -X POST localhost:8080/api/v1/auth/refresh | jq      # rotates the refresh cookie
```

### Import a file (local)

Uploads are asynchronous: `202 Accepted` with the batch and a `Location` to poll; the worker (the
`worker` role, on in this local setup) processes the file in the background.

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"uploader@acme.test","password":"reconflow-local-demo"}' | jq -r .accessToken)
curl -s localhost:8080/api/v1/imports -H "Authorization: Bearer $TOKEN" \
  -F file=@docs/samples/ledger-basic.csv --form-string sourceType=INTERNAL_LEDGER | jq
curl -s localhost:8080/api/v1/imports -H "Authorization: Bearer $TOKEN" \
  -F file=@docs/samples/gateway-basic.csv --form-string sourceType=GATEWAY | jq
curl -s localhost:8080/api/v1/imports -H "Authorization: Bearer $TOKEN" | jq '.content[] | {fileName, status, totalRows, validRows, errorRows}'
curl -s "localhost:8080/api/v1/transactions?transactionDateFrom=2026-10-01&transactionDateTo=2026-10-31&reference=pay-104" \
  -H "Authorization: Bearer $TOKEN" | jq
```

Uploading the same file again is `409 duplicate-file` with `existingBatchId`; `ledger-malformed.csv`
shows row errors at `GET /api/v1/imports/{id}/errors`. [docs/samples/README.md](docs/samples/README.md)
says what every sample produces.

### Reconcile (local)

With both basic files imported, start a run as a user with `RUN_CREATE` (an investigator, uploader
or org admin). It is asynchronous too: `202 Accepted`, then poll until `COMPLETED`.

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"investigator@acme.test","password":"reconflow-local-demo"}' | jq -r .accessToken)
RUN=$(curl -s localhost:8080/api/v1/reconciliation-runs -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-oct-1' \
  -d '{"periodStart":"2026-10-01","periodEnd":"2026-10-02","currency":"AED"}' | jq -r .id)
curl -s localhost:8080/api/v1/reconciliation-runs/$RUN -H "Authorization: Bearer $TOKEN" | jq '{status, summary}'
curl -s localhost:8080/api/v1/reconciliation-runs/$RUN/matches -H "Authorization: Bearer $TOKEN" | jq '.content[] | {ledger: .ledger.reference, gateway: .gateway.reference}'
```

Repeating the same request returns the same run (`200`); a fresh `Idempotency-Key` re-runs the period
without duplicating active exception cases. The expected summary is in
[docs/samples/README.md §1](docs/samples/README.md#1-ledger-basiccsv--gateway-basiccsv).

Then work the PAY-103 case (ledger 750.00, gateway 700.00). Every update sends the `ETag` it last
saw as `If-Match`; a stale one gets `409 stale-version`, a missing one `428`.

```bash
CASE=$(curl -s "localhost:8080/api/v1/exceptions?type=AMOUNT_MISMATCH" -H "Authorization: Bearer $TOKEN" \
  | jq -r '.content[] | select(.reference=="PAY-103") | .id')
curl -s -X PATCH localhost:8080/api/v1/exceptions/$CASE -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/merge-patch+json' -H 'If-Match: "0"' \
  -d '{"status":"IN_REVIEW"}' | jq '{status, version}'
curl -s localhost:8080/api/v1/exceptions/$CASE/notes -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"body":"Gateway confirms a 50 AED fee, ticket GW-8812"}'
curl -s localhost:8080/api/v1/exceptions/$CASE/resolve -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'If-Match: "1"' -d '{"resolutionCode":"GATEWAY_FEE"}' \
  | jq '{status, resolution, history}'
```

`{"assignedTo": "<user id>"}` (or `null`) in the same PATCH assigns the case. An org admin can then reopen it (`POST .../reopen` with `If-Match: "2"` and a `reason`) and read
the whole trail with `GET /api/v1/audit-events?entityType=EXCEPTION_CASE&entityId=$CASE`.

Access tokens last 15 minutes; the refresh token is an `HttpOnly` cookie scoped to `/api/v1/auth`.
Users never sign up: an org admin creates them with a temporary password (`POST /api/v1/members`),
which must be changed at first login. Design: [authentication.plan](plans/authentication.plan),
[ADR 0013](docs/adr/0013-build-authentication-in-house.md).

### Configure a non-local environment

| Variable | Purpose |
|---|---|
| `JWT_PRIVATE_KEY`, `JWT_PUBLIC_KEY` | RS256 key pair as PEM (PKCS#8 private, X.509 public). Required in `prod`: startup fails without them. Never commit them |
| `RECONFLOW_BOOTSTRAP_ADMIN_EMAIL` | One-time: if set and no platform admin exists, creates one and logs its temporary password once. It then creates organizations through `POST /api/v1/platform/organizations` |

For example: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt.pem` and
`openssl pkey -in jwt.pem -pubout -out jwt.pub.pem`, then load the files into the variables.

Row-level security only takes effect when the application connects as a non-superuser that
doesn't own the tables (M10.7); local and test databases connect as superusers, so the RLS tests
switch roles explicitly.

`./mvnw spring-boot:test-run` runs the application against a Testcontainers PostgreSQL instead of
Compose.

### Observe and benchmark (local)

```bash
docker compose --profile observability up -d    # Prometheus :9090, Grafana :3000 (GRAFANA_ADMIN_PASSWORD; on Colima HOST_GATEWAY=192.168.5.2)
python3 scripts/generate_dataset.py --preset 50k --out target/datasets/50k
scripts/bench_import.sh target/datasets/50k 3   # import + reconcile, checked against expected.json
k6 run -e ORG=<org> -e RUN=<run> perf/k6/s1-steady-browsing.js
```

Load scenarios, chaos (`scripts/chaos_kill.sh`), background data (`scripts/preload.sh`) and the
restore drill (`scripts/restore_drill.sh`) are described in [docs/benchmarks.md](docs/benchmarks.md)
and [docs/runbooks](docs/runbooks). All of them talk only to local containers.

### Test

```bash
./mvnw test       # unit tests only (*Test): fast, no Docker
./mvnw verify     # everything: unit + integration tests (*IT, Testcontainers), Spotless check, JaCoCo
./mvnw spotless:apply   # format the code before committing
```

Integration tests need Docker but no local database and no `.env`: they start
`postgres:16.4-alpine` and MinIO (`pgsty/minio`, the compose image), share one set of containers
per run, and Flyway applies the migrations. The job queue runs synchronously in tests
(`JobRunner.runAllQueued`), with 100-row chunks so small files span several commits.
`UploadSizeLimitIT` is the one IT that starts its own context (a real Tomcat port).
Coverage reports land in `target/site/jacoco/` (unit) and `target/site/jacoco-it/` (integration).
CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs `./mvnw verify` on every pull
request.

---

## Roadmap

| # | Milestone | Status |
| - | --------- | ------ |
| M0 | Repository hygiene: license, conventions, protected `main` | In progress |
| M1 | Requirements and system design: docs, ADRs, sample data | Done |
| M2 | Project foundation: profiles, error model, logging, CI | Done |
| M3 | Organizations, users, authentication, tenant isolation | Done |
| M4 | Append-only audit | Done |
| M5 | Transaction ingestion: CSV upload, job queue, validation | Done |
| M6 | Reconciliation engine: matching, idempotent runs | Done |
| M7 | Exception management: workflow, optimistic locking | Done |
| M8 | Reporting and export | Done |
| M9 | Reliability and performance | Done |
| M10 | Security hardening | Not started |
| M11 | Deployment and portfolio | Not started |

[TASKS.md](TASKS.md) lists every task; [plans/](plans/README.md) explains how each phase is built.

### API so far

| Endpoint | Purpose |
| -------- | ------- |
| `POST /api/v1/auth/login`, `/refresh`, `/logout`, `/change-password` | Session lifecycle |
| `GET/POST /api/v1/members`, `PATCH /api/v1/members/{userId}`, `POST …/reset-password`, `POST …/disable` | Member management (`MEMBER_MANAGE`) |
| `GET/POST /api/v1/platform/organizations` | Organizations (platform admins only) |
| `POST /api/v1/imports` | Upload a ledger or gateway CSV (multipart); `202` with the batch (`IMPORT_CREATE`) |
| `GET /api/v1/imports`, `GET /api/v1/imports/{id}` | List imports; one import's status and counts, `Retry-After` while running (`IMPORT_READ`) |
| `GET /api/v1/imports/{id}/errors` | Row errors with row, column, code and raw row, paginated (`IMPORT_READ`) |
| `POST /api/v1/imports/{id}/retry` | Reprocess a `FAILED` import from scratch, with a reason (`IMPORT_CREATE`) |
| `GET /api/v1/transactions` | Search normalized records by date range, source, reference, currency, batch; a slice (`TRANSACTION_READ`) |
| `POST /api/v1/reconciliation-runs` | Request a run for a period, currency and sources; `202` new, `200` repeat, `Idempotency-Key` optional (`RUN_CREATE`) |
| `GET /api/v1/reconciliation-runs`, `GET /api/v1/reconciliation-runs/{id}` | List runs; one run's status, progress and summary (`RUN_READ`) |
| `GET /api/v1/reconciliation-runs/{id}/matches` | A run's matches, both records side by side, paginated (`RUN_READ`) |
| `POST /api/v1/reconciliation-runs/{id}/retry` | Resume a `FAILED` run from its checkpoint, with a reason (`RUN_CREATE`) |

| `GET /api/v1/exceptions` | The work queue: filter by `type`, `status` (default `OPEN`, `IN_REVIEW`), `assignedTo` (`me`, `unassigned` or a user id), `runId`, `createdFrom`/`createdTo`, `amountMin`/`amountMax`; paginated (`EXCEPTION_READ`) |
| `GET /api/v1/exceptions/{id}` | One case: both sides' records (raw and normalized), runs, notes, status history, `ETag` (`EXCEPTION_READ`) |
| `PATCH /api/v1/exceptions/{id}` | Assign/unassign and move between `OPEN` and `IN_REVIEW`; `application/merge-patch+json`, `If-Match` required (`EXCEPTION_UPDATE`) |
| `POST /api/v1/exceptions/{id}/notes` | Add an append-only investigation note (`EXCEPTION_UPDATE`) |
| `POST /api/v1/exceptions/{id}/resolve` | Resolve with a resolution code (and a note where required), `If-Match` (`EXCEPTION_RESOLVE`) |
| `POST /api/v1/exceptions/{id}/reopen` | Reopen a resolved case with a reason, `If-Match`, org admins only (`EXCEPTION_REOPEN`) |
| `GET /api/v1/audit-events?entityType=&entityId=` | One entity's audit timeline (`AUDIT_READ`) |

Permissions per endpoint: [api-design.md §8.1](docs/api-design.md#81-index) and §9.

### Planned API surface

| Endpoint | Purpose |
| -------- | ------- |
| `GET /api/v1/reports/reconciliation` | Summary metrics by run or date range |
| `GET /api/v1/reports/import-quality`, `/exception-ageing` | File quality, case ageing |
| `GET /api/v1/reports/exceptions/export` | A run's exceptions as CSV |

Build order is ingestion → reconciliation → exceptions. Not every endpoint is required for the first working version.

---

## Design principles

- **Correctness over coverage.** Deterministic rules and explicit mismatch outcomes beat clever heuristics that silently mis-match.
- **Idempotency everywhere.** Re-uploading a file or re-running reconciliation must not create duplicate financial records, matches, or active exceptions.
- **Tenant isolation is enforced server-side.** A user from one organization must never read another's records by guessing an ID; hiding rows in a UI is not access control.
- **Audit is append-only.** Important state changes produce immutable events — who acted, on what, when, and why.
- **No speculative infrastructure.** Kafka, Redis, and microservices are added only when a measured need justifies them.
- **Failures are first-class.** Partial imports expose per-row errors; crashed jobs resume or reprocess safely; concurrent edits return a conflict rather than silently overwriting.

## Design decisions

| Question | Decision |
|---|---|
| Duplicate imports | [ADR 0005](docs/adr/0005-duplicate-import-policy.md) |
| Reconciliation-run idempotency | [ADR 0006](docs/adr/0006-reconciliation-run-idempotency.md) |
| Sync vs async processing | [ADR 0003](docs/adr/0003-asynchronous-imports-and-runs.md) |
| Concurrency on exceptions | [ADR 0007](docs/adr/0007-optimistic-locking-on-exceptions.md) |
| Tenant enforcement | [ADR 0008](docs/adr/0008-tenant-isolation.md) |
| Building authentication in-house | [ADR 0013](docs/adr/0013-build-authentication-in-house.md) |
| Currencies per run | [ADR 0009](docs/adr/0009-run-pinned-to-one-currency.md) |

All decisions: [docs/adr/](docs/adr/).

---

## Repository layout

```
paymentreconflow/
├── docs/                 # scalability plan; requirements, architecture, API, rules (M1)
├── plans/                # how each phase is built, and why
├── src/main/java/com/reconflow/paymentreconflow/
│   ├── PaymentreconflowApplication.java
│   ├── organization/ security/ ingestion/ ledger/        # one package per module,
│   ├── reconciliation/ exceptions/ reporting/ audit/     # boundaries checked by ModularityTest
│   └── shared/           # ids, json, error, web, config, jobs
├── src/main/resources/
│   ├── application.yml   # shared defaults; application-<profile>.yml overlays
│   └── db/migration/     # Flyway V1–V4
├── src/test/java/...     # *Test unit tests, *IT Testcontainers integration tests
├── compose.yaml          # local PostgreSQL 16 and MinIO
├── .github/workflows/    # CI
├── projectoverview.md    # full system design brief
├── TASKS.md              # every task, M0–M11
├── CONTRIBUTING.md       # branch, commit and PR conventions
├── LICENSE               # Apache-2.0
├── pom.xml
└── README.md
```

---

## Documentation

| Document | Contents |
| -------- | -------- |
| [requirements.md](docs/requirements.md) | Problem statement, personas, functional and non-functional requirements, scope, assumptions, CSV contracts |
| [reconciliation-rules.md](docs/reconciliation-rules.md) | Normalization, participation, match key, ordered outcomes, exception types and codes, discrepancy keys, state machines, money handling |
| [architecture.md](docs/architecture.md) | Context and containers, modules and table ownership, allowed dependencies, request flows, background processing |
| [database-design.md](docs/database-design.md) | Conventions, ERD, tables, the constraints that enforce correctness, partitioning, tenancy, migration policy |
| [api-design.md](docs/api-design.md) | Conventions, error model and catalogue, pagination, idempotency, concurrency, status codes, endpoint reference |
| [scalability.md](docs/scalability.md) | Capacity plan for 1,000 users, SLOs, load-test scenarios |
| [samples/](docs/samples/README.md) | Sample CSVs and the expected outcome of every row |
| [adr/](docs/adr/) | Architecture Decision Records |

`projectoverview.md` holds the original design brief and remains the source of intent.
