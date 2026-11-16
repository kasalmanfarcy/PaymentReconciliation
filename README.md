# ReconFlow

A payment reconciliation and exception resolution platform. ReconFlow compares a business's internal payment ledger against payment gateway settlements, surfaces the discrepancies, and tracks how each one was investigated and resolved.

> **Project status: foundation done (M2).** Schema (Flyway V1–V3), profiles, module boundaries, error model, request IDs, security defaults, quality gates and CI are in place. No domain code or business endpoints exist yet. See [Roadmap](#roadmap).

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
| Framework | Spring Boot 4.1.2 | Web MVC, validation, data JPA, security, actuator; Spring Modulith 2.1 for module boundaries |
| Database | PostgreSQL | `NUMERIC` for money, FK + unique constraints, transactional writes |
| Migrations | Flyway | V1–V3 applied on startup; applied migrations are never edited |
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
# optionally change DB_PASSWORD and S3_SECRET_KEY
```

The `local` profile imports it with `spring.config.import: optional:file:.env[.properties]`. Real
environment variables take precedence over the file. No other profile reads `.env`: tests get
their database from Testcontainers, and `prod` takes `DB_URL`, `DB_USER` and `DB_PASSWORD` from the
environment only and refuses to start, naming the variable, if one is missing.

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

Flyway applies `V1`–`V3` on first startup. Hibernate runs in `validate` mode, so a mismatch between
the entities and the schema fails the boot rather than silently altering it.

| Profile | Kind | Effect |
|---|---|---|
| `local` | Environment | Reads `.env`, readable logs with the request ID, DEBUG SQL, full health details |
| `prod` | Environment | Secrets from environment variables only, JSON (ECS) logs |
| `test` | Environment | Used by the tests (Testcontainers); lives in `src/test/resources` |
| `api` | Role | Serves HTTP traffic |
| `worker` | Role | Turns on the job worker (`reconflow.jobs.worker.enabled`) |

There is deliberately no default profile. Locally one process runs both roles.

```bash
curl -s localhost:8080/actuator/health        # {"status":"UP",...}
curl -si localhost:8080/api/v1/anything       # 401, application/problem+json, X-Request-Id
```

Everything except `/actuator/health` and `/actuator/info` returns `401` until authentication
arrives in M3. Errors are RFC 9457 Problem Details with `code` and `requestId`
([api-design.md §3](docs/api-design.md#3-error-model)).

`./mvnw spring-boot:test-run` runs the application against a Testcontainers PostgreSQL instead of
Compose.

### Test

```bash
./mvnw test       # unit tests only (*Test): fast, no Docker
./mvnw verify     # everything: unit + integration tests (*IT, Testcontainers), Spotless check, JaCoCo
./mvnw spotless:apply   # format the code before committing
```

Integration tests need Docker but no local database and no `.env`: they start
`postgres:16.4-alpine`, share one container per run, and Flyway applies the migrations to it.
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
| M3 | Organizations, users, authentication, tenant isolation | Not started |
| M4 | Append-only audit | Not started |
| M5 | Transaction ingestion: CSV upload, job queue, validation | Not started |
| M6 | Reconciliation engine: matching, idempotent runs | Not started |
| M7 | Exception management: workflow, optimistic locking | Not started |
| M8 | Reporting and export | Not started |
| M9 | Reliability and performance | Not started |
| M10 | Security hardening | Not started |
| M11 | Deployment and portfolio | Not started |

[TASKS.md](TASKS.md) lists every task; [plans/](plans/README.md) explains how each phase is built.

### Planned API surface

| Endpoint | Purpose |
| -------- | ------- |
| `POST /api/v1/imports` | Upload a transaction file |
| `GET /api/v1/imports/{id}` | Import status and row-level errors |
| `GET /api/v1/transactions` | Search and filter transaction records |
| `POST /api/v1/reconciliation-runs` | Start a reconciliation run |
| `GET /api/v1/reconciliation-runs/{id}` | Run results and status |
| `GET /api/v1/exceptions` | List and filter exceptions |
| `GET /api/v1/exceptions/{id}` | Exception detail with both source records |
| `PATCH /api/v1/exceptions/{id}` | Assign or update an exception |
| `POST /api/v1/exceptions/{id}/resolve` | Resolve with a reason |
| `GET /api/v1/reports/reconciliation` | Summary metrics |

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
│   └── db/migration/     # Flyway V1–V3
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
