# ReconFlow — End-to-End Task List

The full build plan, from an empty Spring Boot skeleton to a deployed, documented portfolio project. Milestones follow the order in [projectoverview.md](projectoverview.md) §10. Each milestone ends in something that works and can be demonstrated.

**How to use this file**

- Tick a box (`[x]`) when the task is merged to `main`.
- **Done when** lines are the acceptance criteria. A task isn't finished until they hold.
- Task IDs (`M3.4`) are for commit messages and branch names, e.g. `feat/M3.4-row-validation`.
- Don't start a milestone until the previous one's **Exit criteria** are met.

---

## M0 — Repository hygiene

- [x] **M0.1** Make the initial commit of the Spring Initializr skeleton.
- [x] **M0.2** Fill in the `pom.xml` metadata: `<name>`, `<description>`, and remove the empty `<licenses>`, `<developers>` and `<scm>` blocks.
- [x] **M0.3** Pick a license and add `LICENSE`.
- [ ] **M0.4** Create a GitHub repository, push `main` and protect it (PRs only, CI must pass). *Note: the "CI must pass" rule is switched on in M2.15, once a CI check exists.*
- [x] **M0.5** Agree on a branch and commit convention (e.g. Conventional Commits plus task IDs) and record it in `CONTRIBUTING.md`.
- [x] **M0.6** Add `.editorconfig` covering indentation, final newline and line endings.

**Exit criteria:** the repository is on GitHub and `./mvnw test` passes on a fresh clone.

---

## M1 — Requirements and system design

Design docs come before code. Each one answers questions from projectoverview.md §11.

### Requirements
- [x] **M1.1** Write `docs/requirements.md`: problem statement, personas (uploader, investigator, org admin, platform admin), functional requirements, non-functional requirements, and v1 scope versus out of scope.
- [x] **M1.2** List the v1 assumptions explicitly: one-to-one matching, a single currency per transaction, CSV uploads only, synthetic data.
- [x] **M1.3** Define the CSV contracts: required and optional columns for the internal ledger and the gateway settlement, the date format, amount format, encoding, and the maximum file size and row count.
- [x] **M1.4** Write sample CSVs under `docs/samples/` covering every scenario: matched, missing on each side, amount mismatch, duplicate within a file, malformed rows.

### Reconciliation rules
- [x] **M1.5** Write `docs/reconciliation-rules.md`: normalization rules (trim, case, currency ISO-4217, timezone to UTC, status mapping), the match key, mismatch definitions, duplicate definitions, and tie-breaking.
- [x] **M1.6** Define the exception types and reason codes, e.g. `MISSING_IN_GATEWAY`, `MISSING_IN_LEDGER`, `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `DUPLICATE_IN_SOURCE`.
- [x] **M1.7** Document the state machines for import batches, runs and exceptions. For every transition, record who may trigger it, whether it needs a reason, and which audit event it emits.
- [x] **M1.8** Specify money handling: `BigDecimal` scale and rounding mode, and `NUMERIC(19,4)` or a justified alternative.

### Architecture and data
- [x] **M1.9** Write `docs/architecture.md`: module boundaries (ingestion, ledger, reconciliation, exceptions, reporting/audit, plus shared/security), allowed dependencies between modules, request flow diagrams, and the deferred async design.
- [x] **M1.10** Write `docs/database-design.md`: ERD, tables, FKs, unique constraints (including the ones that enforce idempotency), indexes and retention.
- [x] **M1.11** Write `docs/api-design.md`: endpoint contracts, error model (RFC 9457 `ProblemDetail`), pagination, filtering, `Idempotency-Key` semantics, `ETag`/`If-Match` or version-based concurrency, and when to return `201` versus `202`.

### Decisions (close the README's open questions)
- [x] **M1.12** Add `docs/adr/` with an ADR for each decision:
  - [x] Duplicate-import policy: content hash, natural key, or both. What a deliberate re-import looks like.
  - [x] Reconciliation run idempotency key: what makes two runs "the same run".
  - [x] Sync or async processing in v1, and its status codes.
  - [x] Concurrency control on exceptions (working assumption: `@Version` returning `409`).
  - [x] Tenant enforcement mechanism.
  - [x] Whether a run can span currencies or is pinned to one.
  - [x] Modular monolith over microservices.

**Exit criteria:** all five docs plus the ADRs are merged, and the README's "Open design questions" section is replaced with links to the ADRs.

---

## M2 — Project foundation

### Dependencies and infrastructure
- [x] **M2.1** Add `spring-boot-starter-data-jpa`, the PostgreSQL driver, `flyway-core` and `flyway-database-postgresql`. *(Done with `spring-boot-starter-flyway` instead of `flyway-core`: in Boot 4 the library alone has no auto-configuration.)*
- [x] **M2.2** Add `spring-boot-starter-actuator` and `spring-boot-starter-security`.
- [x] **M2.3** Add Testcontainers (PostgreSQL) and the `spring-boot-testcontainers` support.
- [x] **M2.4** Add `compose.yaml` with PostgreSQL (pinned version, named volume, healthcheck) and MinIO for S3-compatible file storage (bucket `reconflow-imports`).
- [x] **M2.5** Switch configuration to `application.yml` with `local`, `test` and `prod` profiles. Secrets come from environment variables, never from committed files.
- [x] **M2.6** Set `spring.jpa.hibernate.ddl-auto=validate` so Flyway owns the schema and Hibernate only checks it.
- [x] **M2.7** Set `spring.jpa.open-in-view=false`.

### Code structure
- [x] **M2.8** Create the module packages: `ingestion`, `ledger`, `reconciliation`, `exceptions`, `reporting`, `audit`, `organization`, `security`, `shared`. Add the `api` and `worker` role profiles, which enable different beans (the worker sets `reconflow.jobs.worker.enabled`).
- [x] **M2.9** Enforce module boundaries with ArchUnit or Spring Modulith tests.
- [x] **M2.10** Add a global `@RestControllerAdvice` that returns `ProblemDetail` for validation, not-found, conflict and generic errors.
- [x] **M2.11** Add a request correlation ID: a filter, MDC, and a `X-Request-Id` response header.
- [x] **M2.12** Configure structured (JSON) logging for the `prod` profile.

### Quality gates
- [x] **M2.13** Add Spotless or Checkstyle for formatting.
- [x] **M2.14** Add JaCoCo with a coverage report (no threshold yet).
- [x] **M2.15** Add a GitHub Actions workflow that runs build, tests (with Testcontainers) and format checks on each PR.
- [x] **M2.16** Add a base integration test class that starts the context against a Testcontainers PostgreSQL instance.

**Exit criteria:** `docker compose up` followed by `./mvnw spring-boot:run` starts against PostgreSQL, Flyway applies `V1__initial_schema.sql`, `V2__auth_hardening.sql` and `V3__refresh_token_constraints.sql`, `/actuator/health` returns `UP`, and CI is green.

---

## M3 — Organizations, users and tenant isolation

Tenant isolation comes before any financial data exists, not after.

- [x] **M3.1** Write a migration and entity for `organization`. *(Entity only: V1 already creates the table.)*
- [x] **M3.2** Write a migration and entity for `app_user`, plus user–organization membership with a role: `ORG_ADMIN`, `INVESTIGATOR`, `UPLOADER`, `VIEWER`. *(Entities only: V1 has the tables. `Role` lives in `shared.context`, since `TenantContext.role()` returns it.)*
- [x] **M3.3** Authentication, built in-house as a learning exercise (ADR: why we build it, and what we'd delegate in production). Spring Security does the cryptography; we never hand-roll hashing or JWT parsing.
  - [x] **M3.3a** Password storage: `DelegatingPasswordEncoder` (bcrypt). Document the cost factor and how stored hashes get upgraded.
  - [x] **M3.3b** `POST /api/v1/auth/login` issues a short-lived access JWT (15 min), signed with `NimbusJwtEncoder` (RS256), plus a refresh token.
  - [x] **M3.3c** Refresh tokens: random, stored **hashed** in `refresh_token`, rotated on every use, with reuse detection that revokes the whole token family.
  - [x] **M3.3d** `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout` (revokes the refresh token).
  - [x] **M3.3e** Configure the app as an OAuth2 resource server that validates our own JWTs. The same config later accepts Keycloak's tokens (M10.1).
  - [x] **M3.3f** Brute-force protection: lock the account after 5 failures, return the same error for an unknown user and a wrong password, and rate-limit the login endpoint.
  - [x] **M3.3g** Users are created by an admin with a temporary password and must change it on first login. No public sign-up.
  - [x] **M3.3h** Record login success, failure, lockout, refresh-token reuse and logout in `audit_event`.
  - [x] **M3.3i** All code reads identity only through `CurrentUser` / `TenantContext`, never from the `SecurityContext` directly.
  - [x] **M3.3j** Tests: an expired token gives `401`, a tampered signature gives `401`, a reused refresh token revokes its family, lockout triggers after N failures, and the error messages for unknown user and wrong password are identical.
- [x] **M3.4** Tenant context: resolve the caller's `organizationId` once per request and expose it through a request-scoped bean. *(A `ScopedValue` bound by `TenantContextFilter` instead of a request-scoped bean, so the same mechanism works on worker threads.)*
- [x] **M3.5** Repository convention: every tenant-owned query takes `organizationId`. Add an ArchUnit rule or a review checklist item for it. Add PostgreSQL row-level security policies on every tenant-owned table as a second layer, with `app.org_id` set per transaction (`SET LOCAL` / `set_config(..., true)`).
- [x] **M3.6** Return `404` (not `403`) for another tenant's resource IDs, so IDs can't be probed.
- [x] **M3.7** Add a role-based authorization matrix (`@PreAuthorize`) and document it in `api-design.md`.
- [x] **M3.8** Seed data: Flyway `R__` scripts or a `local`-profile seeder creating two organizations and users for each role. *(A Java seeder switched on by `reconflow.seed.enabled`, which only the `local` profile sets.)*
- [x] **M3.9** **Test:** a user in Organization A cannot read, list, update or resolve any of Organization B's resources. Write one test per endpoint and keep adding them as endpoints appear.

**Exit criteria:** authenticated requests carry a tenant context, and the cross-tenant test suite exists and passes.

---

## M4 — Audit foundation

Built early so later milestones emit events as they go, instead of retrofitting them.

- [x] **M4.1** Write a migration and entity for `audit_event` *(entity only: V1 has the table)*: `organization_id`, `actor_id`, `action`, `entity_type`, `entity_id`, `payload` (JSONB), `request_id`, `created_at`.
- [x] **M4.2** Make it append-only: no update or delete paths in code, and revoke `UPDATE`/`DELETE` from the app's DB role (or add a trigger). *(V1's triggers, now tested; the REVOKE is listed under M10.7.)*
- [x] **M4.3** Add an `AuditService.record(...)` that writes in the **same transaction** as the business change.
- [x] **M4.4** **Test:** a rolled-back business transaction leaves no audit row.

**Exit criteria:** any module can emit audit events transactionally.

---

## M5 — Transaction ingestion

### Job queue
- [ ] **M5.0** DB-backed job queue in `shared.jobs` (moved forward from M9.2, see docs/scalability.md §4): claim with `FOR UPDATE SKIP LOCKED`, leases with heartbeats, a sweeper for expired leases, at most 2 running jobs per organization, exponential backoff, and a `JobHandler` per job type. Enqueue in the same transaction as the business row.

### Data model
- [ ] **M5.1** Write a migration and entity for `import_batch`: source type, status, file name, content hash (SHA-256), stored file reference, total/valid/error row counts, timestamps and `uploaded_by`.
- [ ] **M5.2** Write a migration and entity for `import_row_error`: batch, row number, column, error code, message, raw row.
- [ ] **M5.3** Write a migration and entity for `transaction_record`: org, batch, source type, raw reference, normalized reference, `amount NUMERIC`, currency, transaction type, status, transaction date, raw values (JSONB) and the source row number. *Note: V1 already creates the table, partitioned by month on `transaction_date`; this task maps the entity and the writer.*
- [ ] **M5.4** Add a unique constraint on `(organization_id, source_type, content_hash)` for duplicate-file detection.
- [ ] **M5.5** Add a unique constraint that prevents the same source record being inserted twice by a retried batch, e.g. `(batch_id, source_row_number)`.
- [ ] **M5.6** Add indexes for the match query: `(organization_id, source_type, normalized_reference)` and `(organization_id, transaction_date)`.

### Upload and processing
- [ ] **M5.7** `POST /api/v1/imports` (multipart): check file size, content type, org access and required headers, then create the batch as `RECEIVED`.
- [ ] **M5.8** File storage: write the original file to a `FileStorage` abstraction implemented on S3-compatible storage (MinIO locally), so any worker can read a file uploaded through any API instance.
- [ ] **M5.9** Duplicate-file policy: implement ADR 0005: reject with `409 duplicate-file` and the existing batch ID; a failed batch is retried in place (M5.20), never re-uploaded.
- [ ] **M5.10** CSV parser (Apache Commons CSV or similar) that streams rows instead of loading the whole file into memory. Rows are processed and committed in chunks of 5,000 with a `last_committed_row` resume checkpoint (M5.14).
- [ ] **M5.11** Row validator: required fields, amount format and scale, ISO currency, date parsing and enum values. Collect errors for each row and keep going.
- [ ] **M5.12** Normalizer: produce normalized fields while keeping the raw values intact.
- [ ] **M5.13** Within-file duplicate detection: flag repeated rows without dropping either one.
- [ ] **M5.14** Batch inserts: JDBC batching in chunks of 5,000 rows, one transaction per chunk that also advances `last_committed_row`, so a crashed import resumes where it stopped. IDs are application-generated UUIDv7 (ADR 0010).
- [ ] **M5.15** Status transitions `RECEIVED → PROCESSING → COMPLETED | COMPLETED_WITH_ERRORS | FAILED`, enforced in a single place.
- [ ] **M5.16** Emit audit events for import received, completed and failed.
- [ ] **M5.17** `GET /api/v1/imports/{id}` for status, counts and paginated row errors.
- [ ] **M5.18** `GET /api/v1/imports` to list batches for the organization, paginated.
- [ ] **M5.19** `GET /api/v1/transactions` with filters for source, date range, reference, currency and batch, paginated and sorted.
- [ ] **M5.20** `POST /api/v1/imports/{id}/retry`, an explicit and audited reprocess of a `FAILED` batch.

### Tests
- [ ] **M5.21** Unit tests for the validator and normalizer, one per rule in `reconciliation-rules.md`.
- [ ] **M5.22** Integration test: the sample CSVs from M1.4 produce the expected counts and errors.
- [ ] **M5.23** Uploading the same file twice follows the documented policy.
- [ ] **M5.24** A file with malformed rows ends in `COMPLETED_WITH_ERRORS`, with valid rows persisted and every bad row reported.
- [ ] **M5.25** A crash in the middle of processing, simulated by an exception after N rows, followed by a retry, produces no duplicate `transaction_record` rows.
- [ ] **M5.26** Wrong headers, an empty file or an oversized file are rejected with a clear `ProblemDetail`.

### Partitions
- [ ] **M5.27** Partition-maintenance job: keep `transaction_record` monthly partitions created 3 months ahead (idempotent, daily, on the worker), and alert when `transaction_record_default` is non-empty. Must exist before real data arrives (plans/README.md, ordering exception 3).

**Exit criteria:** both sample files can be uploaded and the normalized transactions inspected through the API, and every failure case in projectoverview.md §6 that relates to imports has a passing test.

---

## M6 — Reconciliation engine

### Data model
- [ ] **M6.1** Write a migration and entity for `reconciliation_run`: org, period start and end, source pair, status, idempotency key, summary counts and totals, `started_at`, `completed_at`, failure reason and `triggered_by`.
- [ ] **M6.2** Write a migration and entity for `reconciliation_match`: run, ledger record, gateway record, match type and amount difference.
- [ ] **M6.3** Add unique constraints so a record can't be matched twice within a run, and on the run's idempotency key.

### Engine
- [ ] **M6.4** Define a `MatchingStrategy` interface and implement `ExactReferenceStrategy` (v1) as set-based SQL inside PostgreSQL, processed one anchor day per chunk with a `last_completed_date` checkpoint (ADR 0012).
- [ ] **M6.5** Exact match: same normalized reference, the same currency and the same transaction type.
- [ ] **M6.6** Amount comparison: references that match but amounts that differ produce `AMOUNT_MISMATCH` and are never counted as matched.
- [ ] **M6.7** Unmatched detection on both sides.
- [ ] **M6.8** Duplicate detection: several candidates for one reference produce a `DUPLICATE` exception, never an arbitrary pick.
- [ ] **M6.9** Persist matches, exceptions and summary counts atomically, or in documented chunks with a resumable checkpoint.
- [ ] **M6.10** Run lifecycle `PENDING → RUNNING → COMPLETED | FAILED`, with the failure reason recorded.
- [ ] **M6.11** Idempotency: re-running the same period and sources returns the existing run, or supersedes it under a documented rule, without duplicating matches or active exceptions.
- [ ] **M6.12** Retrying a `FAILED` run doesn't duplicate matches it had already committed.
- [ ] **M6.13** Emit audit events for run started, completed and failed.

### API
- [ ] **M6.14** `POST /api/v1/reconciliation-runs`, accepting an `Idempotency-Key` header and returning `202` with the run (ADR 0003), or `200` with the existing run for a repeated request (ADR 0006).
- [ ] **M6.15** `GET /api/v1/reconciliation-runs/{id}` for status, summary counts and totals.
- [ ] **M6.16** `GET /api/v1/reconciliation-runs/{id}/matches`, paginated.
- [ ] **M6.17** `GET /api/v1/reconciliation-runs`, listing runs, paginated.

### Tests
- [ ] **M6.18** Engine unit tests driven by the worked example tables in the README and projectoverview.md §5, with exact expected outcomes for each record.
- [ ] **M6.19** Property-style test: every input record ends up in exactly one place (matched, mismatched, unmatched or duplicate), so nothing is lost or counted twice.
- [ ] **M6.20** Running the same reconciliation twice produces no duplicate matches or exceptions.
- [ ] **M6.21** A record from Organization B never takes part in a run for Organization A.
- [ ] **M6.22** `BigDecimal` equality uses `compareTo`, so `1000.0` and `1000.00` count as equal. Add a regression test for it.

**Exit criteria:** uploading both sample files and starting a run produces the expected matches and exceptions, and repeating the run changes nothing.

---

## M7 — Exception management

### Data model
- [ ] **M7.1** Write a migration and entity for `exception_case`: org, run, type, reason code, status, linked records, `assigned_to`, resolution code, resolution note, `@Version`, and timestamps.
- [ ] **M7.2** Write a migration and entity for `exception_note`, as an append-only investigation history.
- [ ] **M7.3** Add a partial unique index so the same discrepancy has only one *active* case, e.g. `WHERE status <> 'RESOLVED'`.
- [ ] **M7.4** Keep resolution history: reopening creates a history entry and never overwrites the previous resolution.

### Workflow
- [ ] **M7.5** Status transitions `OPEN → IN_REVIEW → RESOLVED`, plus `RESOLVED → OPEN` (reopen), enforced centrally with the permissions from M1.7.
- [ ] **M7.6** `GET /api/v1/exceptions` with filters for type, status, assignee, run, date and amount range, paginated.
- [ ] **M7.7** `GET /api/v1/exceptions/{id}`, including both source records side by side, notes and history.
- [ ] **M7.8** `PATCH /api/v1/exceptions/{id}` to assign it or move it to `IN_REVIEW`. Requires `If-Match` or a version, and returns `409` on a stale version.
- [ ] **M7.9** `POST /api/v1/exceptions/{id}/notes` to add an investigation note.
- [ ] **M7.10** `POST /api/v1/exceptions/{id}/resolve` with a required resolution code and reason.
- [ ] **M7.11** `POST /api/v1/exceptions/{id}/reopen` with a required reason, limited to authorized roles.
- [ ] **M7.12** Emit an audit event for every assignment, status change, note, resolution and reopen.
- [ ] **M7.13** `GET /api/v1/audit-events?entityType=&entityId=` for the timeline of a single entity.

### Tests
- [ ] **M7.14** Concurrency test: two simultaneous resolves give one success and one `409`, with no silent overwrite.
- [ ] **M7.15** Invalid transitions (e.g. resolving an `OPEN` case without a reason, or resolving an already `RESOLVED` case) are rejected.
- [ ] **M7.16** Each role can only perform the transitions it's allowed.
- [ ] **M7.17** After a reopen, the earlier resolution is still visible in the history.
- [ ] **M7.18** Add the cross-tenant tests for every exception endpoint.

**Exit criteria:** a finance user can take an exception from `OPEN` to `RESOLVED` with a full audit trail, and concurrent edits are detected.

---

## M8 — Reporting

- [ ] **M8.1** `GET /api/v1/reports/reconciliation?runId=` or by date range: matched count and total, unmatched counts and totals on each side, mismatch count and net difference, duplicate count, and open versus resolved exceptions.
- [ ] **M8.2** Import quality report: error rows by error code, for each batch and over time.
- [ ] **M8.3** Exception ageing: open exceptions grouped by age bucket and by assignee.
- [ ] **M8.4** CSV export of exceptions for a run.
- [ ] **M8.5** Use aggregate SQL queries rather than loading entities into memory. Check the plans with `EXPLAIN ANALYZE`.
- [ ] **M8.6** **Test:** report totals reconcile against raw counts from the sample dataset.

**Exit criteria:** a single API call answers the four questions in the README ("Which match? Which don't? Why? Who resolved them?") for a run.

---

## M9 — Reliability and performance

### Reliability
- [ ] **M9.1** Recover jobs left stuck in `PROCESSING` or `RUNNING` after a restart, using a heartbeat or lease plus a startup sweep.
- [ ] **M9.2** ~~Move import and reconciliation processing off the request thread.~~ Moved to **M5.0**: ADR 0003 made processing asynchronous from v1. This task now hardens the queue built there (fencing, graceful shutdown, queue metrics).
- [ ] **M9.3** Add retry with backoff for transient DB failures, and keep it away from validation failures.
- [ ] **M9.4** Failure-injection tests: kill processing at several points, then reprocess and assert the final state is identical to a clean run.

### Performance
- [ ] **M9.5** Generate a 50k-row synthetic dataset (and 500k for stress testing, plus the 6-month preload for load tests) with a script under `scripts/`.
- [ ] **M9.6** Benchmark import throughput and reconciliation time, and run load scenarios S1–S6 from docs/scalability.md §7 with k6. Record the results in `docs/benchmarks.md`.
- [ ] **M9.7** Tune batch sizes, indexes and fetch strategies, and add checks for N+1 queries.
- [ ] **M9.8** Make sure every list endpoint paginates and has a maximum page size.

### Observability
- [ ] **M9.9** Micrometer metrics: rows imported, rows rejected, run duration, matches and exceptions per run, open exception count.
- [ ] **M9.10** Expose a Prometheus endpoint, plus an optional Grafana dashboard in `compose.yaml`.
- [ ] **M9.11** Liveness and readiness health groups.

### Kafka (only if M9.6 shows a need)
- [ ] **M9.12** Write an ADR stating the measured problem Kafka solves. If no measurement justifies it, record that decision and skip M9.13–M9.15.
- [ ] **M9.13** Use the transactional outbox pattern to publish `ImportCompleted` and `ReconciliationRequested` events.
- [ ] **M9.14** Make consumers idempotent, with a dead-letter topic.
- [ ] **M9.15** Testcontainers Kafka integration tests.

### Data lifecycle
- [ ] **M9.16** Archival job: after 13 months, export a `transaction_record` partition (and the matches and exception links that reference it) to Parquet in object storage, verify counts and checksums, then detach and drop the partition (docs/scalability.md §5.3).
- [ ] **M9.17** Quarterly restore drill: restore a backup to a fresh instance, check row counts and a sample run's totals, and record the time taken against the RTO (≤ 1 h).

**Exit criteria:** the 50k-row benchmark is documented, crash and reprocess tests pass, and metrics are visible.

---

## M10 — Security hardening

- [ ] **M10.1** Migrate login to OIDC with a Keycloak container in Compose. Only the security package should change; if anything outside it has to change, that's a design lesson worth writing down. Write a comparison ADR covering what we built in M3.3, what Keycloak gives us, and what each costs.
- [ ] **M10.2** CSV injection protection on export: escape cells that start with `=`, `+`, `-` or `@`.
- [ ] **M10.3** Upload limits enforced at the servlet level as well as in code.
- [ ] **M10.4** Rate limiting on the upload and run endpoints.
- [ ] **M10.5** Dependency vulnerability scanning in CI (OWASP Dependency-Check or GitHub Dependabot).
- [ ] **M10.6** Review logs for PII and financial data, and mask references where appropriate.
- [ ] **M10.7** Least-privilege DB roles: separate users for migrations and for the application.

**Exit criteria:** a security checklist in `docs/security.md` is complete, with each item linked to its test or config.

---

## M11 — Deployment and portfolio

### Packaging and deployment
- [ ] **M11.1** Multi-stage `Dockerfile` (or Buildpacks via `spring-boot:build-image`) running as a non-root user.
- [ ] **M11.2** A `compose.yaml` profile that runs the full stack: app, PostgreSQL, and optionally Prometheus and Grafana.
- [ ] **M11.3** A CI job that builds and pushes the image on merge to `main`.
- [ ] **M11.4** Deploy to a host (Render, Fly.io, Railway or a small VM) with a managed PostgreSQL.
- [ ] **M11.5** Run Flyway migrations as part of the deploy, with a rollback note for each migration.

### Documentation and demo
- [ ] **M11.6** Update the README: real setup steps, the current API surface, and roadmap statuses.
- [ ] **M11.7** OpenAPI spec through springdoc, with Swagger UI enabled on non-prod profiles.
- [ ] **M11.8** A Postman or Bruno collection that walks the demo: upload ledger → upload gateway file → run → list exceptions → resolve → report.
- [ ] **M11.9** A reproducible demo dataset and a `scripts/demo.sh` that runs the walkthrough end to end.
- [ ] **M11.10** Final `architecture.md` with diagrams that match the code as built.
- [ ] **M11.11** A trade-offs write-up: what was deferred and why, what you would change at 100× scale, and lessons learned.
- [ ] **M11.12** A short demo video or GIF in the README.

**Exit criteria:** someone can clone the repo, run a single command, and watch the full workflow from upload to resolution.

---

## Later — v2 scope (after v1 ships)

From projectoverview.md §5. Each item needs its own rules doc update and ADR.

- [ ] One-to-many and many-to-one matching for aggregated settlements
- [ ] Refunds, chargebacks and fees as distinct transaction types
- [ ] Fee-aware matching (gross versus net settlement)
- [ ] Configurable settlement-date windows
- [ ] Bank statement as a third source (three-way reconciliation)
- [ ] Manual match and unmatch for ambiguous cases, with audit
- [ ] Per-organization configurable CSV column mappings for different gateway formats
- [ ] Scheduled imports from SFTP or object storage, and gateway API pulls
- [ ] Notifications when an exception is assigned or ageing past an SLA
- [ ] A minimal web UI for exception investigation
- [ ] Redis, only if a measured caching or coordination need appears

---

## Cross-cutting checklist (apply to every PR)

- [ ] Money is `BigDecimal` / `NUMERIC`, never `double` or `float`, and compared with `compareTo`
- [ ] Every tenant-owned query is scoped by `organizationId`
- [ ] New endpoints have cross-tenant and authorization tests
- [ ] State changes go through the central transition logic and emit an audit event
- [ ] Schema changes are a new Flyway migration; applied migrations are never edited
- [ ] List endpoints paginate
- [ ] Docs in `docs/` are updated when behaviour changes
