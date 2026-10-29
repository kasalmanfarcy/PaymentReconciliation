# ReconFlow

A payment reconciliation and exception resolution platform. ReconFlow compares a business's internal payment ledger against payment gateway settlements, surfaces the discrepancies, and tracks how each one was investigated and resolved.

> **Project status: design phase.** This repository currently contains the Spring Boot skeleton only. No domain code, persistence layer, or endpoints exist yet. See [Roadmap](#roadmap).

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

  Later (only when justified): Kafka · background workers
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

Valid transitions, required reasons, and authorization rules are defined in `docs/reconciliation-rules.md` (planned).

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
| Framework | Spring Boot 4.1.2 | `webmvc` + `validation` only, so far |
| Database | PostgreSQL | `NUMERIC` for money, FK + unique constraints, transactional writes |
| Migrations | Flyway | Planned — not yet added |
| Build | Maven (wrapper) | `./mvnw`, no system Maven required |
| Testing | JUnit + Testcontainers | Planned |
| Async | Kafka | Deliberately deferred |
| Cache | Redis | Deliberately deferred; only if a concrete need appears |
| Local infra | Docker Compose | Planned |

---

## Getting started

### Prerequisites

- **Java 25** — verify with `java -version`
- No system Maven needed; use the bundled wrapper (`./mvnw`, executable)
- Docker — not required yet, will be once PostgreSQL is wired up

### Run

```bash
git clone <repository-url>
cd paymentreconflow
./mvnw spring-boot:run
```

The application starts on `http://localhost:8080`. It currently exposes **no endpoints** — there is no database configuration and no domain code, so a successful startup only proves the context loads.

### Test

```bash
./mvnw test
```

The only test present is the default `contextLoads` smoke test.

---

## Roadmap

| # | Milestone | Status |
| - | --------- | ------ |
| 01 | Requirements and system design | **In progress** — `docs/` not yet written |
| 02 | Project foundation: JPA, PostgreSQL, Flyway, config, tests | Not started — skeleton only |
| 03 | Transaction ingestion: CSV upload, row validation, dedupe | Not started |
| 04 | Reconciliation engine: matching, mismatches, duplicates, reports | Not started |
| 05 | Exception management: assignment, resolution, RBAC, audit | Not started |
| 06 | Reliability & performance: reprocessing, concurrency, metrics | Not started |
| 07 | Deployment & portfolio: Docker, docs, demo dataset | Not started |

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

## Open design questions

These must be resolved before the persistence layer is written:

- Duplicate-import policy: content hash, natural key, or both? What does a deliberate re-import look like?
- Idempotency key for reconciliation runs — what makes two runs "the same run"?
- Sync vs. async imports in v1, and whether the API returns `201 Created` or `202 Accepted`.
- Concurrency control on exception resolution: optimistic locking (`@Version` + `409`) is the working assumption.
- Mechanism for tenant enforcement — request-scoped context plus repository methods that always take `organizationId`.
- Whether a run may span currencies, or is pinned to one.

---

## Repository layout

```
paymentreconflow/
├── docs/                 # requirements, architecture, DB design, API, matching rules (planned)
├── src/main/java/com/reconflow/paymentreconflow/
│   └── PaymentreconflowApplication.java
├── src/main/resources/
│   └── application.properties
├── src/test/java/...
├── projectoverview.md    # full system design brief
├── pom.xml
└── README.md
```

---

## Documentation

Planned under `docs/`:

| Document | Contents |
| -------- | -------- |
| `requirements.md` | Problem statement, users, functional and non-functional requirements, scope |
| `architecture.md` | Module boundaries, request flows, ERD, deferred async design |
| `database-design.md` | Tables, constraints, indexes, indexes that enforce correctness |
| `api-design.md` | Contracts, error model, pagination, idempotency and concurrency semantics |
| `reconciliation-rules.md` | Normative matching rules, tie-breaking, state transitions, reason codes |

`projectoverview.md` holds the original design brief and remains the source of intent.
