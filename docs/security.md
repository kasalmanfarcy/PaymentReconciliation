# Security controls

**Status:** M10 (security hardening), built control by control · **Owner:** whoever changes a control
updates its row in the same PR.

One row per control: what it stops, what enforces it, and what proves it. A control without an
"Enforced by" link is a control we only think we have; a control without a "Proven by" link is one
nobody has seen fail. Accepted risks are listed too, marked as such, with the reasoning.

---

## 1. Controls

### 1.1 Tenant isolation and authorization

| ID | Control | Threat | Enforced by | Proven by | Status |
|----|---------|--------|-------------|-----------|--------|
| S-01 | Membership checked on every request; `X-Organization-Id` must name one of the caller's active organizations | IDOR across organizations | [TenantContextFilter](../src/main/java/com/reconflow/paymentreconflow/security/tenant/TenantContextFilter.java), [TenantResolver](../src/main/java/com/reconflow/paymentreconflow/security/tenant/TenantResolver.java) | [CrossTenantIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/CrossTenantIT.java), [CrossTenantCoverageIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/CrossTenantCoverageIT.java) (fails when an endpoint has no case), [TenantResolverTest](../src/test/java/com/reconflow/paymentreconflow/security/tenant/TenantResolverTest.java) | ✅ |
| S-02 | Every tenant query filters by `organization_id` from `TenantContext` | A forgotten filter | Repository convention (CONTRIBUTING.md) | [TenantRepositoryConventionTest](../src/test/java/com/reconflow/paymentreconflow/tenancy/TenantRepositoryConventionTest.java) | ✅ |
| S-03 | Another tenant's IDs answer `404`, never `403` | Probing IDs for existence | [ADR 0008](adr/0008-tenant-isolation.md) | [NotFoundNotForbiddenIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/NotFoundNotForbiddenIT.java) | ✅ |
| S-05 | Row-level security on every tenant-owned table, effective for the running application: it connects as `reconflow_app` (not owner, not superuser, `NOBYPASSRLS`) | A query that forgets `WHERE organization_id` | [V4](../src/main/resources/db/migration/V4__row_level_security.sql) policies, [bootstrap-roles.sql](../infra/db/bootstrap-roles.sql), [V10](../src/main/resources/db/migration/V10__app_role_grants.sql), `spring.datasource.username` in [application.yml](../src/main/resources/application.yml) | [AppRolePrivilegesIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/AppRolePrivilegesIT.java) (raw `SELECT count(*) FROM exception_case` as the app returns one org's rows), [RowLevelSecurityIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/RowLevelSecurityIT.java), [TenantTransactionListenerIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/TenantTransactionListenerIT.java), [RlsCoverageIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/RlsCoverageIT.java); the whole IT suite runs as the app role | ✅ |
| S-06 | Partitions of `transaction_record` aren't reachable by name (no policy of their own) | Bypassing RLS by querying `transaction_record_2026_10` | [V10](../src/main/resources/db/migration/V10__app_role_grants.sql) `REVOKE ALL` on partitions; `SECURITY DEFINER` functions for partition jobs | [AppRolePrivilegesIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/AppRolePrivilegesIT.java) `aPartitionQueriedByNameIsNotAWayAroundRls` | ✅ |
| S-04 | Permissions resolved per request from the database, never from token claims; code checks permissions, never roles | Stale or forged privileges; privilege drift | [RolePermissions](../src/main/java/com/reconflow/paymentreconflow/security/authz/RolePermissions.java), `@RequiresPermission` on every controller method | [AuthorizationMatrixIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/AuthorizationMatrixIT.java), [RolePermissionsTest](../src/test/java/com/reconflow/paymentreconflow/security/authz/RolePermissionsTest.java), [PermissionDocsTest](../src/test/java/com/reconflow/paymentreconflow/tenancy/PermissionDocsTest.java), [ArchitectureRulesTest](../src/test/java/com/reconflow/paymentreconflow/ArchitectureRulesTest.java) | ✅ |

### 1.2 Data integrity and output

| ID | Control | Threat | Enforced by | Proven by | Status |
|----|---------|--------|-------------|-----------|--------|
| S-10 | `audit_event`, `exception_note`, `exception_status_history` are append-only (triggers) | Rewriting history to hide an action | [V1](../src/main/resources/db/migration/V1__initial_schema.sql), [V6](../src/main/resources/db/migration/V6__exception_history_append_only.sql) triggers | [AuditAppendOnlyIT](../src/test/java/com/reconflow/paymentreconflow/audit/AuditAppendOnlyIT.java), [ExceptionHistoryAppendOnlyIT](../src/test/java/com/reconflow/paymentreconflow/exceptions/ExceptionHistoryAppendOnlyIT.java) | ✅ |
| S-13 | The application can't change the schema, disable a trigger, rewrite append-only tables or the migration history (DML only; `UPDATE`/`DELETE`/`TRUNCATE` revoked on the audit tables) | A compromised app (SQL injection, RCE) destroying evidence or the schema | [V10](../src/main/resources/db/migration/V10__app_role_grants.sql), [bootstrap-roles.sql](../infra/db/bootstrap-roles.sql) | [AppRolePrivilegesIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/AppRolePrivilegesIT.java) (`DROP TABLE`, `ALTER TABLE audit_event DISABLE TRIGGER ALL`, `UPDATE audit_event`, `INSERT INTO flyway_schema_history` all denied) | ✅ |
| S-14 | `SECURITY DEFINER` functions pin `search_path` and are executable by the app only | Privilege escalation through a definer function | [V10](../src/main/resources/db/migration/V10__app_role_grants.sql) | [AppRolePrivilegesIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/AppRolePrivilegesIT.java) `theDefinerFunctionsCanOnlyBeCalledByTheApp` | ✅ |
| S-11 | CSV exports escape formula-like free-text cells (`= + - @`, tab, CR, LF, after spaces, full-width forms); money stays numeric | CSV/formula injection in Excel | [CsvCell](../src/main/java/com/reconflow/paymentreconflow/reporting/export/CsvCell.java) (M8.4) | [CsvCellTest](../src/test/java/com/reconflow/paymentreconflow/reporting/export/CsvCellTest.java), [ExceptionExportIT](../src/test/java/com/reconflow/paymentreconflow/reporting/ExceptionExportIT.java), [ExportInjectionIT](../src/test/java/com/reconflow/paymentreconflow/reporting/ExportInjectionIT.java) (M10.2: uploaded `=1+1`, `+`, `-`, `@` references come out as `'=1+1`…, stored unchanged, the `-5.0000` difference stays numeric; seen failing with the escape removed) | ✅ |
| S-12 | Error bodies never carry stack traces, SQL, class names or whether an email exists | Information disclosure | [GlobalExceptionHandler](../src/main/java/com/reconflow/paymentreconflow/shared/error/GlobalExceptionHandler.java), `server.error.include-stacktrace: never` in [application-prod.yml](../src/main/resources/application-prod.yml) | [GlobalExceptionHandlerTest](../src/test/java/com/reconflow/paymentreconflow/shared/error/GlobalExceptionHandlerTest.java) | ✅ |

### 1.3 Infrastructure and operations

| ID | Control | Threat | Enforced by | Proven by | Status |
|----|---------|--------|-------------|-----------|--------|
| S-41 | Request rate on the endpoints that create background work (uploads, runs and their retries): token bucket per user per organization, `429 rate-limited` + `Retry-After` | One client in a retry loop flooding the queue and storage | [RateLimitInterceptor](../src/main/java/com/reconflow/paymentreconflow/shared/ratelimit/RateLimitInterceptor.java) (Bucket4j, bounded Caffeine map), `@RateLimited` on the controllers, `reconflow.rate-limit.*` in [application.yml](../src/main/resources/application.yml) | [RateLimitIT](../src/test/java/com/reconflow/paymentreconflow/reliability/RateLimitIT.java) (capacity + 1 uploads → exactly one 429 with `Retry-After`; a colleague in the same org unaffected; runs have their own bucket) | ✅ |
| S-42 | Exact cap on an organization's QUEUED + RUNNING jobs (50), checked under a per-organization advisory lock in the enqueue transaction | The per-instance buckets allow N× the rate with N nodes; noisy-neighbour queue exhaustion | [JobQueue](../src/main/java/com/reconflow/paymentreconflow/shared/jobs/JobQueue.java), [JobStore](../src/main/java/com/reconflow/paymentreconflow/shared/jobs/JobStore.java) `lockAndCountPending`, [V11](../src/main/resources/db/migration/V11__job_pending_index.sql) | [RateLimitIT](../src/test/java/com/reconflow/paymentreconflow/reliability/RateLimitIT.java) (10 threads at cap − 1 → exactly cap pending; an upload at the cap → 429 and no batch row) | ✅ |
| S-40 | Upload size limited by the servlet container while it parses the multipart body (50 MB file, 51 MB request), again in code, and the two must agree at startup; a reverse proxy should cap the body too (`client_max_body_size 51m`) | Disk or memory exhaustion by oversized uploads | `spring.servlet.multipart.*`, `server.tomcat.max-swallow-size`, `max-http-form-post-size`, `connection-timeout` in [application.yml](../src/main/resources/application.yml); [ImportReceiver](../src/main/java/com/reconflow/paymentreconflow/ingestion/application/ImportReceiver.java); [UploadLimitsCheck](../src/main/java/com/reconflow/paymentreconflow/ingestion/application/UploadLimitsCheck.java) | [UploadSizeLimitIT](../src/test/java/com/reconflow/paymentreconflow/ingestion/UploadSizeLimitIT.java) (over a real socket: limit + 1 byte → `413 file-too-large`, no batch row, no stored object; exactly the limit → `202`), [UploadLimitsCheckTest](../src/test/java/com/reconflow/paymentreconflow/ingestion/application/UploadLimitsCheckTest.java) | ✅ (proxy: M11) |
| S-20 | Metrics only on the internal management port; `/actuator/prometheus` denied on the public port | Reconnaissance (routes, volumes) | [SecurityConfig](../src/main/java/com/reconflow/paymentreconflow/security/config/SecurityConfig.java), `management.server.port` | [ManagementPortIT](../src/test/java/com/reconflow/paymentreconflow/reliability/ManagementPortIT.java) | ✅ |

### 1.4 Supply chain and secrets (M10.5)

| ID | Control | Threat | Enforced by | Proven by | Status |
|----|---------|--------|-------------|-----------|--------|
| S-30 | Dependabot: weekly grouped update PRs for Maven, GitHub Actions and compose images | Running known-vulnerable versions because nobody noticed an update | [.github/dependabot.yml](../.github/dependabot.yml) | Dependabot PRs on GitHub (user step: §3) | ⏳ needs the repository settings in §3 |
| S-31 | Dependency review blocks a PR that adds a dependency with a high or critical advisory | A vulnerable dependency merged in a PR | [dependency-review.yml](../.github/workflows/dependency-review.yml), [dependency-submission.yml](../.github/workflows/dependency-submission.yml) (resolved tree, so transitive dependencies are seen) | A test PR with a known-vulnerable version (user step: §3) | ⏳ |
| S-32 | Weekly full scan: OWASP Dependency-Check (CVSS ≥ 7 fails), Trivy over the packaged jar | New CVEs in versions we already ship | [dependency-check.yml](../.github/workflows/dependency-check.yml), `dependency-check-maven` in [pom.xml](../pom.xml), [suppressions](../config/dependency-check-suppressions.xml) (each with a reason and an expiry) | Trivy run locally on the M10 jar: 0 HIGH/CRITICAL/MEDIUM findings (2026-11-16); the scheduled run's report artifact | ✅ Trivy · ⏳ Dependency-Check needs `NVD_API_KEY` |
| S-33 | Secret scanning of the full git history on every PR and push | A committed key | [secret-scan.yml](../.github/workflows/secret-scan.yml) (gitleaks), [.gitleaks.toml](../.gitleaks.toml) | Run locally over all 164 commits: two findings, both `Idempotency-Key` example UUIDs in docs, allowlisted by a line-precise regex; no secrets | ✅ |
| S-34 | GitHub secret scanning with push protection | A key pushed before CI runs | Repository setting | — | ⏳ user step (§3) |

---

| S-35 | No secret in git, the image or `application.yml`; a missing secret fails startup in `prod` | Leaked or silently defaulted credentials | `.gitignore` (`.env`), [RequiredPropertiesCheck](../src/main/java/com/reconflow/paymentreconflow/shared/config/RequiredPropertiesCheck.java) with `reconflow.required-properties` in [application-prod.yml](../src/main/resources/application-prod.yml); test passwords generated per run ([TestDatabase](../src/test/java/com/reconflow/paymentreconflow/support/TestDatabase.java)) | [RequiredPropertiesCheckTest](../src/test/java/com/reconflow/paymentreconflow/shared/config/RequiredPropertiesCheckTest.java), gitleaks (S-33) | ✅ |

### 1.5 Secrets and their rotation

| Secret | Local | CI / tests | Deployed | Rotation |
|---|---|---|---|---|
| `DB_APP_PASSWORD`, `DB_MIGRATOR_PASSWORD` | `.env` (gitignored) | generated per test run | platform secret store; the migrator's only on the migrate step (M11.5) | re-run `bootstrap-roles.sql` with the new password, update the store, restart, verify. Grants live on roles, not passwords, so nothing else changes |
| `DB_USER`/`DB_PASSWORD` (superuser) | `.env` | container default | break-glass only, never in an app config | the platform's procedure |
| `S3_ACCESS_KEY`/`S3_SECRET_KEY` | `.env` (MinIO) | Testcontainers MinIO | a key scoped to one bucket | issue new key, update store, restart, revoke old |
| `NVD_API_KEY` | — | GitHub Actions secret | — | request a new one, replace the secret |

## 2. Accepted risks

| ID | Risk | Why accepted | Revisit when |
|----|------|--------------|--------------|
| R-03 | Request-rate buckets are in memory per API instance: N instances allow up to N× the configured rate | The expensive resource (the queue) is protected exactly by S-42 in the database; a shared bucket would cost Redis (rejected until measured, scalability.md D8) or a row lock per request | Abuse shows up despite S-42, or instances exceed ~3 |
| R-02 | `max-swallow-size` is 100 MB, not 2 MB | api-design.md promises a readable `413` for an oversized file; with a small value Tomcat resets the connection and the client sees a network error. Swallowed bytes are discarded, not stored, and `connection-timeout` bounds slow senders | A proxy in front enforces the limit (M11): then lower it |
| R-01 | Dependency-Check runs weekly, not per PR | Minutes per run, mostly the NVD download; dependency review covers the per-PR question | A CVE slips through between runs |

---

## 3. Steps that live outside the repository

These can't be done from code; until they are, the rows above that depend on them stay ⏳.

1. **Settings → Code security:** enable *Dependency graph*, *Dependabot alerts*, *Dependabot security
   updates*, *Secret scanning* and *Push protection*.
2. **Settings → Secrets and variables → Actions:** add `NVD_API_KEY` (free:
   https://nvd.nist.gov/developers/request-an-api-key), then run *owasp-dependency-check* once by hand
   (*Actions → owasp-dependency-check → Run workflow*) and keep its report.
3. **Branch protection on `main`:** add `dependency-review` and `gitleaks` as required checks next to
   `build`.
4. **Prove the gate once:** open a throwaway PR that adds `org.apache.commons:commons-text:1.9`
   (CVE-2022-42889, "Text4Shell"); dependency review must fail it. Close it unmerged.
