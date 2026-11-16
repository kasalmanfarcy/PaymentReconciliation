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
| S-04 | Permissions resolved per request from the database, never from token claims; code checks permissions, never roles | Stale or forged privileges; privilege drift | [RolePermissions](../src/main/java/com/reconflow/paymentreconflow/security/authz/RolePermissions.java), `@RequiresPermission` on every controller method | [AuthorizationMatrixIT](../src/test/java/com/reconflow/paymentreconflow/tenancy/AuthorizationMatrixIT.java), [RolePermissionsTest](../src/test/java/com/reconflow/paymentreconflow/security/authz/RolePermissionsTest.java), [PermissionDocsTest](../src/test/java/com/reconflow/paymentreconflow/tenancy/PermissionDocsTest.java), [ArchitectureRulesTest](../src/test/java/com/reconflow/paymentreconflow/ArchitectureRulesTest.java) | ✅ |

### 1.2 Data integrity and output

| ID | Control | Threat | Enforced by | Proven by | Status |
|----|---------|--------|-------------|-----------|--------|
| S-10 | `audit_event`, `exception_note`, `exception_status_history` are append-only (triggers) | Rewriting history to hide an action | [V1](../src/main/resources/db/migration/V1__initial_schema.sql), [V6](../src/main/resources/db/migration/V6__exception_history_append_only.sql) triggers | [AuditAppendOnlyIT](../src/test/java/com/reconflow/paymentreconflow/audit/AuditAppendOnlyIT.java), [ExceptionHistoryAppendOnlyIT](../src/test/java/com/reconflow/paymentreconflow/exceptions/ExceptionHistoryAppendOnlyIT.java) | ✅ |
| S-11 | CSV exports escape formula-like free-text cells (`= + - @`, tab, CR, LF, after spaces, full-width forms); money stays numeric | CSV/formula injection in Excel | [CsvCell](../src/main/java/com/reconflow/paymentreconflow/reporting/export/CsvCell.java) (M8.4) | [CsvCellTest](../src/test/java/com/reconflow/paymentreconflow/reporting/export/CsvCellTest.java), [ExceptionExportIT](../src/test/java/com/reconflow/paymentreconflow/reporting/ExceptionExportIT.java) | ✅ |
| S-12 | Error bodies never carry stack traces, SQL, class names or whether an email exists | Information disclosure | [GlobalExceptionHandler](../src/main/java/com/reconflow/paymentreconflow/shared/error/GlobalExceptionHandler.java), `server.error.include-stacktrace: never` in [application-prod.yml](../src/main/resources/application-prod.yml) | [GlobalExceptionHandlerTest](../src/test/java/com/reconflow/paymentreconflow/shared/error/GlobalExceptionHandlerTest.java) | ✅ |

### 1.3 Infrastructure and operations

| ID | Control | Threat | Enforced by | Proven by | Status |
|----|---------|--------|-------------|-----------|--------|
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

## 2. Accepted risks

| ID | Risk | Why accepted | Revisit when |
|----|------|--------------|--------------|
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
