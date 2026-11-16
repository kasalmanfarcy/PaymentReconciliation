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

Things v1 treats as true without proof. Writing them down means we notice when one stops being true, and the right-hand column says what breaks if it does.

| ID | Assumption | Consequence if false |
|---|---|---|
| A1 | Matching is one-to-one: one ledger record corresponds to at most one gateway record | Aggregated settlements (one gateway row paying several orders) show up as MISSING and DUPLICATE cases until v2 adds one-to-many matching |
| A2 | Each transaction has a single currency; there is no FX conversion | Cross-currency settlement needs FX rates and a tolerance (v2+). Today it surfaces as `CURRENCY_MISMATCH` |
| A3 | A reconciliation run covers one currency, so its totals can be summed | Multi-currency totals would be meaningless without FX. See [ADR 0009](adr/0009-run-pinned-to-one-currency.md) |
| A4 | Both sources carry the same business reference for a payment (the merchant's order or payment reference) | Otherwise matching needs fuzzy rules or a mapping table, which v1 deliberately doesn't have |
| A5 | Both sources report the **payment** timestamp, not the settlement date, as the transaction time | A gateway that only reports settlement dates shifts records by a day or more; settlement-date windows are v2 |
| A6 | Input is CSV in the format in §7, uploaded through the API; all data in this project is synthetic | Real customer data adds PII handling (M10.6) and per-customer formats (v2 column mappings) |
| A7 | A reference identifies one payment within an organization and source; a repeat is a duplicate, not a new payment | If a source reuses references (e.g. daily counters), every reuse looks like a duplicate; the key would need a date component |
| A8 | Amounts are positive; the transaction type (`PAYMENT`, `REFUND`, `CHARGEBACK`, `FEE`) says the direction | A source with signed amounts (refunds as `-50.00`) would be rejected row by row; it would need a per-source sign convention |

---

## 7. CSV file contracts

The exact file formats the importer accepts. The upload checks (M5.7), the row validator (M5.11), the samples ([samples/README.md](samples/README.md)) and the API error messages all derive from this section. **Change the contract here first, then the code.**

How the accepted values are turned into canonical ones (trimming, upper-casing, UTC dates, status mapping) is normalization, defined once in [reconciliation-rules.md §1](reconciliation-rules.md#1-normalization). This section only says what is *valid*.

### 7.1 General format (both sources)

| Property | Rule | If violated |
|---|---|---|
| Encoding | UTF-8. A leading byte-order mark (BOM) is accepted and stripped. Invalid bytes are never replaced with `�` | Invalid UTF-8 in the header → upload rejected `400 invalid-encoding`. Invalid UTF-8 further in → batch `FAILED`, `failure_reason = invalid-encoding at row N` |
| Delimiter | Comma | No separate error: a semicolon file has one header column, so it fails the header check |
| Quoting | RFC 4180. Fields containing commas, quotes or line breaks are wrapped in `"`; a `"` inside a quoted field is doubled (`""`). Spaces around a field are part of the field | Row error `MALFORMED_ROW` (see §7.5 for the unterminated-quote case) |
| Header row | Required, first line. Names are matched case-insensitively after trimming. Column order doesn't matter. Unknown columns are allowed: ignored for matching, kept in `raw_values` | Missing required column → `400 missing-columns` at upload, listing them. A header name that repeats → `400 duplicate-columns` |
| Field count | Every data row has as many fields as the header | Row error `MALFORMED_ROW` |
| Line endings | LF or CRLF, mixed allowed | — |
| Empty lines | Skipped and not counted as rows | — |
| Size | ≤ 50 MB | `413 file-too-large` at upload |
| Rows | 1 – 500,000 data rows | Zero bytes, no header, or a header with no data row → `400 empty-file` at upload. More than 500,000 → batch `FAILED` with `failure_reason = too-many-rows` |
| File type | Filename ends in `.csv` (any case), and the part's `Content-Type` is `text/csv`, `text/plain`, `application/vnd.ms-excel` (what Windows sends for `.csv` when Excel is installed) or `application/octet-stream` | `415 unsupported-media-type`. The content type is only a first filter; the real test is parsing the header |
| Dates | `transaction_date`: ISO-8601 calendar date `YYYY-MM-DD` | Row error `INVALID_DATE` |
| Timestamps | `transaction_at`: ISO-8601 date-time **with an offset**, e.g. `2026-10-01T09:15:00+04:00` or `2026-10-01T05:15:00Z`. A time without an offset is rejected: it's ambiguous, and guessing a zone moves payments across midnight | Row error `INVALID_TIMESTAMP` |
| Date window | The record's transaction date (after rule N7) is no more than 2 years in the past and no more than 31 days in the future, relative to today in UTC. Both limits are configuration (`reconflow.import.oldest-transaction-date`, `newest-transaction-date`) | Row error `DATE_OUT_OF_RANGE` |
| Amounts | Plain decimal: optional leading `+`, digits, optional `.` and digits. No thousands separators, no currency symbols, no exponent. At most 15 integer digits and 4 decimal places. Must be > 0 | Row errors `INVALID_AMOUNT`, `AMOUNT_NOT_POSITIVE`, `AMOUNT_SCALE_EXCEEDED`, `AMOUNT_OUT_OF_RANGE` (§7.5) |
| Currency | ISO-4217 alphabetic code, case-insensitive in the file; must be in the JDK's `Currency.getAvailableCurrencies()` | Row error `INVALID_CURRENCY` |
| Reference | Non-blank; at most 128 characters after normalization | Row errors `MISSING_REQUIRED_FIELD`, `REFERENCE_TOO_LONG` |

Why reject instead of fix: every silent fix (dropping a thousands separator, guessing a time zone, rounding a fifth decimal) is a decision about someone's money made without telling them. A row error is visible, has a row number, and the user can correct the source.

### 7.2 Internal ledger (`source_type = INTERNAL_LEDGER`)

| Column | Required | Example | Maps to |
|---|---|---|---|
| `reference` | Yes | `PAY-101` | `raw_reference` (as is), `normalized_reference` (N1–N4) |
| `amount` | Yes | `1000.00` | `amount` (N6) |
| `currency` | Yes | `AED` | `currency` (N5) |
| `status` | Yes | `SUCCESS` | `status` (N9) |
| `transaction_at` | One of these two columns must exist, and each row must fill at least one | `2026-10-01T09:15:00+04:00` | `transaction_at`; `transaction_date` = its UTC date (N7) |
| `transaction_date` | (see above) | `2026-10-01` | `transaction_date`, used only when `transaction_at` is blank (N8) |
| `transaction_type` | No (blank → `PAYMENT`) | `REFUND` | `transaction_type` (N10) |
| `description`, `order_id`, `customer_id`, anything else | No | | `raw_values` only |

At upload, a ledger header with neither `transaction_at` nor `transaction_date` is rejected with `missingColumns: ["transaction_at or transaction_date"]`. A row where both are blank gets `MISSING_REQUIRED_FIELD` on `transaction_at`.

### 7.3 Gateway settlement (`source_type = GATEWAY`)

| Column | Required | Example | Maps to |
|---|---|---|---|
| `merchant_reference` | Yes | `PAY-101` | `raw_reference`, `normalized_reference` |
| `amount` | Yes | `1000.00` | `amount`: the **gross** amount. Fees are not deducted in v1 |
| `currency` | Yes | `aed` | `currency` |
| `status` | Yes | `captured` | `status` (N9) |
| `transaction_at` | Yes | `2026-10-01T05:15:00Z` | `transaction_at`, `transaction_date` (N7) |
| `transaction_type` | No (blank → `PAYMENT`) | `refund` | `transaction_type` |
| `gateway_transaction_id`, `fee_amount`, `settlement_date`, anything else | No | | `raw_values` only (fee-aware matching is v2) |

The gateway calls its reference `merchant_reference` and uses lower-case currencies and its own status words. Real gateways never use your ledger's names, and a contract that shows this is more honest than pretending both files are identical. In v1 the mapping for each source type is fixed in code; per-organization mappings are v2.

### 7.4 Upload-time rejections

Checked synchronously before the file is stored, so the user gets an answer at once and nothing is created. All are RFC 9457 problems ([api-design.md §3](api-design.md#3-error-model)).

| HTTP | `code` | When |
|---|---|---|
| 400 | `empty-file` | Zero bytes, no header line, or a header with no data row |
| 400 | `missing-columns` | A required column is missing; extension member `missingColumns` lists them |
| 400 | `duplicate-columns` | Two header names are equal after trimming and lower-casing |
| 400 | `invalid-encoding` | The header isn't valid UTF-8 |
| 409 | `duplicate-file` | Same bytes already imported for this organization and source type; extension member `existingBatchId` ([ADR 0005](adr/0005-duplicate-import-policy.md)) |
| 413 | `file-too-large` | Over 50 MB |
| 415 | `unsupported-media-type` | Not a `.csv` filename, or a content type outside the list in §7.1 |

### 7.5 Row errors

A row error never stops the import. The importer records every problem in the row (one error per field, plus whole-row errors), skips the row, and continues. Each error is stored in `import_row_error` with the row number, the column (`NULL` for whole-row errors), the code, a readable message and the raw row text.

| Code | Column | When |
|---|---|---|
| `MALFORMED_ROW` | — | The row breaks RFC 4180 quoting, or its field count differs from the header's. Field positions can't be trusted, so no other check runs on this row |
| `MISSING_REQUIRED_FIELD` | the field | A required field is blank after trimming |
| `INVALID_AMOUNT` | `amount` | Not a plain decimal: `1,000.00`, `AED 10`, `1E+3`, `.5`, `abc` |
| `AMOUNT_NOT_POSITIVE` | `amount` | A plain decimal that is zero or negative: `0.00`, `-5.00` |
| `AMOUNT_SCALE_EXCEEDED` | `amount` | More than 4 decimal places: `10.12345` |
| `AMOUNT_OUT_OF_RANGE` | `amount` | More than 15 integer digits |
| `INVALID_CURRENCY` | `currency` | Not an ISO-4217 code known to the JDK: `XYZ`, `DIRHAM` |
| `INVALID_DATE` | `transaction_date` | Not `YYYY-MM-DD`: `01/10/2026`, `2026-02-30` |
| `INVALID_TIMESTAMP` | `transaction_at` | Not an ISO-8601 date-time with offset |
| `DATE_OUT_OF_RANGE` | `transaction_at` or `transaction_date` | Outside the date window in §7.1 |
| `UNKNOWN_STATUS` | `status` | Not in the status mapping (rule N9) |
| `UNKNOWN_TRANSACTION_TYPE` | `transaction_type` | Not blank and not `PAYMENT`, `REFUND`, `CHARGEBACK` or `FEE` (any case) |
| `REFERENCE_TOO_LONG` | `reference` / `merchant_reference` | Longer than 128 characters after normalization |

Amount checks run in the order of the table and stop at the first failure, so one bad amount gets one error: `-5.00` is `AMOUNT_NOT_POSITIVE`, not also something else.

**Row numbers** are 1-based data-record numbers. The header is not counted, empty lines are not counted, and a quoted field that spans two lines is still one record.

**An unterminated quote** (a field opens with `"` and never closes) swallows everything up to the end of the file, so no later row can be read reliably. The importer records one `MALFORMED_ROW` at the record where the quote opened and stops reading. Rows before it are kept, and the batch ends `COMPLETED_WITH_ERRORS` as usual.

**Repeated rows are not errors.** Two identical rows are both imported and both flagged as duplicates (rule D1 in [reconciliation-rules.md §4](reconciliation-rules.md#4-outcomes-evaluated-in-order)), because only a human can tell an export glitch from a real double charge.

**How a batch ends** (state machine in [reconciliation-rules.md §7.1](reconciliation-rules.md#71-import-batch)):

| Situation | Status |
|---|---|
| Every row valid | `COMPLETED` |
| At least one valid row and at least one row error | `COMPLETED_WITH_ERRORS` |
| Every row invalid, or the file couldn't be read to the end for a reason other than an unterminated quote (invalid encoding, too many rows, file missing from storage, retries exhausted) | `FAILED`, with `failure_reason` |

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
