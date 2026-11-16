# ReconFlow — Requirements

**Status:** Accepted for v1 · **Tasks:** TASKS.md M1.1–M1.3 · **Date:** 2026-11-16

This document says what ReconFlow v1 does, for whom, how well, and what it deliberately leaves out. When someone asks "should it do X?", the answer should be findable here. The rules the engine follows live in [reconciliation-rules.md](reconciliation-rules.md); the capacity numbers behind the NFRs live in [scalability.md](scalability.md).

---

## 1. Problem statement

A business that takes card or wallet payments records each sale in its own system (the **internal ledger**), and its payment gateway records the same payment in a **settlement report**. The two should agree, but often don't: a payment is missing from one side, the amounts differ because of a fee or a data-entry slip, a row appears twice in a gateway export, or a timestamp near midnight puts the same payment on different days in the two files.

Today finance teams find these differences by hand, usually with spreadsheets and `VLOOKUP`. It works at a few hundred rows a day and falls apart at tens of thousands: discrepancies get missed, nobody can say who decided that a difference was "just a fee", and repeating last month's check gives a different answer because someone edited the sheet.

ReconFlow replaces the spreadsheet with a deterministic, auditable process. Users upload both files, the system normalizes them and matches them with documented rules, every difference becomes a tracked **exception case**, and every action on a case (assignment, note, resolution, reopen) is recorded in an append-only audit log. The same inputs always give the same result, and every result can be explained.

---

## 2. Personas

The personas map one-to-one onto the roles in the `membership.role` CHECK constraint, plus the platform-level `app_user.is_platform_admin` flag. What each role may do is defined once, as permissions, in [plans/authentication.plan §5](../plans/authentication.plan); the table below is the human summary.

| Persona | Role in the system | Goals | Typical actions |
|---|---|---|---|
| Uploader (operations or finance assistant) | `UPLOADER` | Get today's files in; see and fix file errors | Upload ledger and gateway files, watch import status, read row errors, retry a failed import, start a run |
| Investigator (finance analyst) | `INVESTIGATOR` | Clear the exception queue accurately | Start runs, read everything in the organization, assign cases, add notes, move cases to review, resolve them |
| Organization admin (finance manager) | `ORG_ADMIN` | Oversight, accountability, team management | Everything an investigator can do, plus upload, reopen resolved cases, manage members, read the audit log |
| Viewer (auditor, controller) | `VIEWER` | Check results without changing anything | Read-only access to imports, transactions, runs, exceptions and reports |
| Platform admin (ReconFlow operator) | `is_platform_admin = true` | Onboard and suspend customer organizations | Create, list and suspend organizations. **No** access to any tenant's financial data |

The platform admin is deliberately not a super-user. An operator who can read every tenant's payments is a single account whose compromise leaks everything, so the two permission sets are disjoint (authentication.plan §5).

---

## 3. Functional requirements

Each requirement names the TASKS.md tasks that implement it.

### Organizations, users and access

| ID | Requirement | Tasks |
|---|---|---|
| FR-01 | An org admin creates users with a temporary password that must be changed on first login. There is no self sign-up. | M3.3g |
| FR-02 | A user signs in, may belong to several organizations, and works within one organization per request. | M3.3, M3.4 |
| FR-03 | An org admin changes a member's role, resets their password or disables them; the change applies on the member's next request. | M3.3, authentication.plan §4.5 |
| FR-04 | A platform admin creates and suspends organizations, and creates each new organization's first org admin. | M3.1, M3.3 |

### Ingestion

| ID | Requirement | Tasks |
|---|---|---|
| FR-05 | An uploader uploads an internal-ledger or gateway CSV. Files with missing required headers, the wrong type, no data rows or over the size limit are rejected immediately, before any processing. | M5.7, M5.26 |
| FR-06 | Accepted files are processed in the background. The user can see the batch status, row counts and every row error with its row number, column and reason. | M5.10–M5.17 |
| FR-07 | Uploading a file whose content was already imported for the same organization and source type is rejected and points to the original batch. | M5.4, M5.9, M5.23 |
| FR-08 | A failed import can be retried explicitly, with a reason, without re-uploading the file. | M5.20 |
| FR-09 | Users search normalized transactions by source, date range, reference, currency and batch. | M5.19 |

### Reconciliation

| ID | Requirement | Tasks |
|---|---|---|
| FR-10 | A user starts a reconciliation run for a period, one currency and a source pair. Repeating the same request returns the same run instead of creating a second. | M6.11, M6.14 |
| FR-11 | A run classifies every in-scope record as matched, amount mismatch, currency mismatch, missing on one side, or duplicate, and stores summary counts and totals. | M6.4–M6.9 |
| FR-12 | A failed run can be retried and resumes without duplicating work it had already committed. | M6.12 |
| FR-13 | Users list runs and page through a run's matches. | M6.15–M6.17 |

### Exceptions

| ID | Requirement | Tasks |
|---|---|---|
| FR-14 | Every discrepancy becomes exactly one active exception case, even when several runs see it. | M6.6, M7.3 |
| FR-15 | Investigators list and filter cases, view both source records side by side, assign cases, add notes, move them to review and resolve them with a resolution code. | M7.5–M7.10 |
| FR-16 | Org admins reopen resolved cases with a reason; the earlier resolution stays visible in the history. | M7.4, M7.11, M7.17 |
| FR-17 | Two people editing the same case at once cannot silently overwrite each other; the later one is told the case changed. | M7.8, M7.14 |

### Audit and reporting

| ID | Requirement | Tasks |
|---|---|---|
| FR-18 | Every state change and security event is recorded in an append-only audit log in the same transaction as the change; org admins can read the timeline of any entity. | M4, M3.3h, M5.16, M6.13, M7.12, M7.13 |
| FR-19 | Reports show, per run or date range, matched / unmatched / mismatch / duplicate counts and totals, import quality by error code, and exception ageing by bucket and assignee. | M8.1–M8.3 |
| FR-20 | A run's exceptions can be exported to CSV, safe to open in a spreadsheet. | M8.4, M10.2 |

---

## 4. Non-functional requirements

Each NFR is measurable and names the test or measurement that proves it. The performance numbers come from [scalability.md §6](scalability.md#6-performance-targets-slos), which derives them from a load of 1,000 users in 100 organizations.

| ID | Category | Requirement | Measured by |
|---|---|---|---|
| NFR-01 | Correctness | Every imported record ends a run in exactly one outcome (matched, mismatched, missing, duplicate or excluded); nothing is lost or counted twice | Property test M6.19 |
| NFR-02 | Integrity | No duplicate transaction records, matches or active exception cases after any crash and retry | Failure-injection tests M5.25, M6.12, M9.4 |
| NFR-03 | Isolation | No user can read or change another organization's data; probing another tenant's IDs returns `404` | Cross-tenant suite M3.9, M6.21, M7.18 |
| NFR-04 | Auditability | Every state change has an audit event written in the same transaction; a rolled-back change leaves no audit row; audit rows can't be updated or deleted | Tests M4.2, M4.4 |
| NFR-05 | Concurrency | Concurrent edits to one exception: exactly one succeeds, the others get `409`, and no update is lost | Test M7.14, load scenario S6 |
| NFR-06 | Performance | Upload → `COMPLETED` for a 100k-row file: p95 < 2 min. Reconciliation of a 100k × 100k day: p95 < 1 min. Read endpoints p95 < 300 ms and write endpoints p95 < 500 ms at 100 concurrent users | Benchmarks M9.6, k6 scenarios S1–S4 |
| NFR-07 | Fairness | One organization can't starve others: at most 2 running jobs per organization; job queue wait p95 < 2 min at the 09:00 peak | Load scenario S3 |
| NFR-08 | Availability | 99.5% monthly for the API | Health checks and synthetic probes after deploy (M11) |
| NFR-09 | Recoverability | A crashed job resumes or is retried with a final state identical to a clean run; RPO ≤ 5 min, RTO ≤ 1 h | M9.1, M9.4, quarterly restore drill |
| NFR-10 | Security | Passwords bcrypt-hashed; access tokens live 15 min; an account locks after 5 failed logins; unknown user and wrong password give identical responses | M3.3 tests (M3.3j) |
| NFR-11 | Observability | Every request has a request ID in its logs and its response; import, run and exception metrics are exported | M2.11, M9.9 |
| NFR-12 | Data lifecycle | Transaction data stays queryable for 13 months, then is archived; the hot database stays under ~450 GB at the target load | Partition maintenance and archival jobs (scalability.md §5.3) |

---

## 5. Scope

### 5.1 In scope for v1

- Two-way reconciliation of an internal ledger against one payment gateway.
- Exact one-to-one matching on normalized reference + transaction type, within one currency per run.
- Payments as the main flow. Refunds, chargebacks and fees are stored with their type and matched only like-for-like (a refund only against a refund).
- CSV upload through the REST API, one fixed format per source type ([§7](#7-csv-file-contracts)).
- Asynchronous imports and runs (`202 Accepted`, then poll).
- Exception workflow with assignment, notes, resolution codes, reopen and full history.
- In-house JWT authentication, role-based permissions, tenant isolation with PostgreSQL row-level security as a second layer.
- Append-only audit log, reports, and CSV export.

### 5.2 Out of scope for v1, and where it goes

One sentence each; the details belong to the phase that builds them.

| Out of v1 | Where it goes |
|---|---|
| Bank statements as a third source (three-way reconciliation) | TASKS.md "Later" |
| One-to-many and many-to-one matching for aggregated settlements | TASKS.md "Later" |
| Fee-aware gross-versus-net matching | TASKS.md "Later" |
| Refund, chargeback and fee flows linked to their original payment | TASKS.md "Later" |
| Configurable settlement-date windows (matching across the run period's edge) | TASKS.md "Later" |
| Manual match and unmatch of ambiguous records | TASKS.md "Later" |
| Per-organization CSV column mappings | TASKS.md "Later" |
| SFTP, object-storage drops and gateway API pulls | TASKS.md "Later" |
| Notifications for assignment and SLA ageing | TASKS.md "Later" |
| A web UI | TASKS.md "Later" |
| Fuzzy or probabilistic reference matching | Not planned; see assumption A4 |
| Cross-currency matching with FX rates | Not planned; see assumption A2 and [ADR 0009](adr/0009-run-pinned-to-one-currency.md) |
| Keycloak / OIDC login | M10.1 |
| Kafka | Only if M9.12's measurement justifies it |

---

## 6. Assumptions

_Added in M1.2._

---

## 7. CSV file contracts

_Added in M1.3._

---

## 8. Glossary

| Term | Meaning |
|---|---|
| Organization (tenant) | A customer business. Every business row carries its `organization_id`. |
| Source type | Where a file came from: `INTERNAL_LEDGER` or `GATEWAY`. |
| Import batch | One uploaded file and its processing state. |
| Transaction record | One valid row of a file, normalized, with its raw values kept. |
| Raw value | Exactly what the file said, kept for investigators and auditors. |
| Normalized value | The canonical form used for matching (rules N1–N11 in reconciliation-rules.md). |
| Reconciliation run | One execution of the matching rules over a period, a currency and a source pair. |
| Group | All in-scope records sharing a normalized reference and transaction type. The engine classifies groups, not pairs. |
| Match | A persistent link between one ledger record and one gateway record with equal amounts. |
| Exception case | A discrepancy that needs a human: missing, mismatched or duplicated records. |
| Discrepancy key | The deterministic identity of a discrepancy, so the same problem seen by two runs is one case. |
| Resolution code | Why a case was closed, from a fixed list. |
| Idempotent | Safe to repeat: doing it twice has the same effect as doing it once. |
| Excluded record | A record in the period that doesn't take part in matching (status not `SUCCESS`, or its group has no record in the run's currency). Counted, never silently dropped. |
