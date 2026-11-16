# API design

**Status:** Accepted for v1; the endpoint reference grows as each milestone lands · **Task:** TASKS.md M1.11 · **Date:** 2026-11-16

The contract every controller follows. If a controller needs to break a convention here, change this document first.

Authentication and authorization are designed in [plans/authentication.plan](../plans/authentication.plan); this document links to it instead of repeating it.

---

## 1. Conventions

- **Base path** `/api/v1`. Resource names are plural and kebab-case: `/imports`, `/reconciliation-runs`, `/exceptions`.
- **JSON** request and response bodies (`application/json`), except uploads (`multipart/form-data`), merge patches (`application/merge-patch+json`), CSV exports (`text/csv`) and errors (`application/problem+json`).
- **Field names** are camelCase: `periodStart`, `amountDifference`.
- **IDs** are UUID strings (UUIDv7). The resource's own ID field is always `id`.
- **Timestamps** are ISO-8601 in UTC with `Z`: `2026-10-01T05:15:00Z`. **Dates** are `YYYY-MM-DD`.
- **Money** is a **string** in plain notation at the stored scale, always next to a `currency` field: `{"amount": "1000.0000", "currency": "AED"}`. Never a JSON number, because JavaScript clients parse numbers into binary doubles ([reconciliation-rules.md §8](reconciliation-rules.md#8-money-handling), rule MN7). Money in query parameters is a plain decimal string too.
- **Enums** are upper-case strings with exactly the database values: `COMPLETED_WITH_ERRORS`, `AMOUNT_MISMATCH`.
- **Nulls** are included as `null` rather than omitted, so every response of a type has the same shape.
- **Every response** carries `X-Request-Id`. Clients may send their own (letters, digits, `-`, `_`, up to 64 characters); otherwise the server generates one.
- **Entities are never serialized directly.** Each endpoint returns a DTO, so internal columns (`storage_key`, `content_hash`, `password_hash`) can't leak and the API isn't coupled to the schema.

---

## 2. Authentication and tenant selection

Full design: [authentication.plan §3–§4](../plans/authentication.plan).

- **`Authorization: Bearer <access JWT>`** on every route except `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout` (cookie-authenticated) and `/actuator/health/**`. The access token lives 15 minutes; the refresh token is an `HttpOnly` cookie scoped to `/api/v1/auth`.
- **`X-Organization-Id: <uuid>`** selects the organization on every org-scoped route. The token carries identity only; membership and permissions are looked up per request, so a role change or removal applies on the next request.
  - Header absent and the user has exactly one membership → that organization is used.
  - Header absent and the user has several → `400 organization-required`.
  - Header present but the user isn't a member, or the organization is suspended → `404 organization-not-found`, so the organization's existence isn't confirmed.
  - Header not a UUID → `400 validation-failed`.
- **Platform routes** (`/api/v1/platform/**`) take no organization header and require `PLATFORM_ORG_MANAGE`, granted only from `app_user.is_platform_admin` read from the database. A platform admin has **no** access to org-scoped routes (`404` on all of them).
- While a temporary password is pending (`pwd_change` claim), every route except `POST /auth/change-password` and `POST /auth/logout` returns `403 password-change-required`.
- `POST /auth/change-password` is about the caller, not an organization: it needs only an active user and takes no `X-Organization-Id`, so a user with several memberships (or none) can still change their password.
- Login answers `200 {accessToken, expiresIn, memberships: [{organizationId, name, role}]}` plus the refresh cookie; `memberships` lists active organizations only. Login is rate-limited per client IP (10 attempts, refilled at 10 a minute): over the limit is `429 rate-limited` with `Retry-After`.

---

## 3. Error model

Every error is an RFC 9457 `ProblemDetail` with `Content-Type: application/problem+json`, produced by one global handler (M2.10). Besides the standard members (`type`, `title`, `status`, `detail`, `instance`) it always has:

- **`code`**: a stable, machine-readable kebab-case string. Clients branch on `code`, never on `title` or `detail`, which are for humans and may be reworded.
- **`type`**: `https://reconflow.dev/problems/<code>`.
- **`requestId`**: the same value as the `X-Request-Id` header, so a user can quote it and support can find the logs.

Some errors add extension members (`existingBatchId`, `missingColumns`, `duplicateColumns`, `currentVersion`, `currentStatus`, `errors`).

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
X-Request-Id: 4f1c2a9e8b7d4c3a

{
  "type": "https://reconflow.dev/problems/duplicate-file",
  "title": "File already imported",
  "status": 409,
  "detail": "This file was already imported as batch 01928c7e-5b1a-7c3e-9f00-2a4b6c8d0e1f.",
  "instance": "/api/v1/imports",
  "code": "duplicate-file",
  "requestId": "4f1c2a9e8b7d4c3a",
  "existingBatchId": "01928c7e-5b1a-7c3e-9f00-2a4b6c8d0e1f"
}
```

Validation errors add an `errors` array, one entry per problem:

```json
{
  "type": "https://reconflow.dev/problems/validation-failed",
  "title": "Invalid request",
  "status": 400,
  "code": "validation-failed",
  "requestId": "9b2e4d6f8a0c1e3b",
  "errors": [
    { "field": "periodEnd", "code": "must-not-be-before-start", "message": "periodEnd must be on or after periodStart" }
  ]
}
```

**Never in an error body:** stack traces, exception class names, SQL, constraint names, or whether an email exists. A `500` says only "an unexpected error occurred; quote the requestId".

### 3.1 Error catalogue

| `code` | Status | When |
|---|---|---|
| `validation-failed` | 400 | Bean validation failure on a body or parameter, malformed JSON, unknown sort field, malformed header value; `errors[]` lists each problem |
| `organization-required` | 400 | No `X-Organization-Id` and the user belongs to several organizations |
| `missing-columns` | 400 | Upload: required CSV columns missing (`missingColumns`) |
| `duplicate-columns` | 400 | Upload: a CSV header name repeats |
| `empty-file` | 400 | Upload: zero bytes, no header, or no data row |
| `invalid-encoding` | 400 | Upload: the CSV header isn't valid UTF-8 |
| `password-policy` | 400 | A new password doesn't meet the policy |
| `unauthenticated` | 401 | Missing, expired, tampered or otherwise invalid token; disabled user |
| `invalid-credentials` | 401 | Login failed. Identical for unknown email, wrong password and locked account |
| `forbidden` | 403 | Authenticated member without the required permission |
| `password-change-required` | 403 | A temporary password must be changed first |
| `not-found` | 404 | Unknown ID **or another tenant's ID**: the two are indistinguishable on purpose |
| `organization-not-found` | 404 | `X-Organization-Id` names an organization the user isn't a member of, or a suspended one |
| `method-not-allowed` | 405 | The path exists but not with this HTTP method (added in M2.10) |
| `not-acceptable` | 406 | The `Accept` header asks for a representation the endpoint can't produce (added in M2.10) |
| `duplicate-file` | 409 | Upload: same content already imported (`existingBatchId`) |
| `import-in-progress` | 409 | Run request while the organization has an import `RECEIVED` or `PROCESSING` |
| `stale-version` | 409 | `If-Match` doesn't match the current version (`currentVersion`) |
| `invalid-transition` | 409 | The state machine doesn't allow this from the current state (`currentStatus`); covers retrying a batch or run that isn't `FAILED`, resolving a resolved case, assigning a resolved case |
| `active-case-exists` | 409 | Reopen would create a second active case for the same discrepancy (`existingCaseId`) |
| `last-org-admin` | 409 | Demoting or disabling the organization's last active org admin |
| `already-a-member` | 409 | Adding a user who is already a member of this organization |
| `run-in-progress` | 409 | Requesting or retrying a run while another run of the same organization, period, sources and currency is `PENDING` or `RUNNING` (`existingRunId`); added in M6 |
| `file-too-large` | 413 | Upload over 50 MB |
| `unsupported-media-type` | 415 | Upload isn't a `.csv` with an accepted content type; or a body in the wrong content type |
| `idempotency-key-reused` | 422 | Same `Idempotency-Key`, different request body |
| `invalid-assignee` | 422 | The assignee isn't a member of this organization with `EXCEPTION_UPDATE` |
| `precondition-required` | 428 | `If-Match` missing on a versioned update |
| `rate-limited` | 429 | Too many requests (login: M3.3f; uploads and runs: M10.4); with `Retry-After` |
| `internal-error` | 500 | Anything unexpected; no details in the body |

The codes live in one class, `shared.error.ProblemCodes`, kept in sync with this table. An error Spring raises itself with a status that has no row here gets `http-<status>` (for example `http-408`); clients should fall back on the HTTP status for any code they don't recognise.

Why `400` and not `422` for upload content errors: `400` is the general "your request is wrong" status, and splitting hairs between 400 and 422 per check helps nobody. `422` is kept for the two cases where the request is well-formed but conflicts with semantics the client couldn't check itself (a reused key, an assignee who can't take the case).

---

## 4. Pagination, sorting, filtering

**Parameters:** `?page=0&size=50&sort=createdAt,desc`.

- `page` is 0-based. `size` defaults to **50**, maximum **200**; larger values are **clamped** to 200, not rejected (clients asking for "everything" get a full page, not an error).
- `sort=<field>,<asc|desc>`, repeatable. Sort fields are **whitelisted per endpoint** and mapped to columns in code; an unknown field is `400 validation-failed`. User input never reaches SQL as a column name.
- Every list has a stable default order with a unique tiebreaker (`createdAt desc, id desc`), so pages don't shift or repeat between requests.

**Page response** (Spring Data's stable `PagedModel` shape; never serialize a raw `PageImpl`, whose JSON isn't a stable contract):

```json
{
  "content": [ … ],
  "page": { "number": 0, "size": 50, "totalElements": 1234, "totalPages": 25 }
}
```

**Slice response**, used by `GET /transactions`, where a total count over millions of rows would cost more than the page itself:

```json
{
  "content": [ … ],
  "page": { "number": 0, "size": 50, "hasNext": true }
}
```

**Filters** are query parameters named after the response field they filter: `status`, `type`, `sourceType`, `currency`, `assignedTo`. Multi-value filters repeat the parameter (`status=OPEN&status=IN_REVIEW`). Ranges use suffixes: `<field>From` / `<field>To` for dates (inclusive), `<field>Min` / `<field>Max` for amounts. Examples:

```
GET /api/v1/exceptions?status=OPEN&type=AMOUNT_MISMATCH&assignedTo=me&createdFrom=2026-10-01&createdTo=2026-10-31&amountMin=100
GET /api/v1/transactions?transactionDateFrom=2026-10-01&transactionDateTo=2026-10-31&sourceType=GATEWAY&reference=pay-103
```

`assignedTo` also accepts `me` and `unassigned`. A filter value that names something outside the caller's organization (a user, a batch) simply matches nothing; it never confirms the thing exists elsewhere.

**IDs of other organizations (ADR 0008, M3.6).** Every lookup is by `(id, organization_id)` in one indexed query, so another organization's resource is simply not found:

- In a **path**, it is `404 not-found` with the same body as an ID that never existed; the body mentions only the ID the caller sent, and both cases cost the same single query, so neither the status, the body nor the timing tells them apart. Never `403`, which would confirm the ID is real.
- In a **request body** (an assignee, the batches a run should use), it is treated exactly like an unknown ID: the endpoint's own rule for an unknown reference applies, `422 invalid-assignee` for an assignee and `404 not-found` for a referenced resource. Never a separate "belongs to another organization" error.
- The organization itself is checked first: a non-member's `X-Organization-Id` is `404 organization-not-found` before any permission check, so a non-member never learns whether they'd have had the right.

Offset pagination slows down deep into a large list (`OFFSET 100000` still reads 100,000 rows). That's acceptable for v1's work queues; keyset pagination (`WHERE (created_at, id) < (:lastCreatedAt, :lastId)`) is the known fix if deep paging is ever needed.

---

## 5. Idempotency

**Uploads** need no key: the SHA-256 of the file content is their natural idempotency key. A client that retries a timed-out upload gets `409 duplicate-file` with `existingBatchId`, which is exactly the batch it was trying to create ([ADR 0005](adr/0005-duplicate-import-policy.md)).

**`POST /reconciliation-runs`** accepts an optional `Idempotency-Key` header: 1–128 printable ASCII characters. What makes two requests "the same run" ([ADR 0006](adr/0006-reconciliation-run-idempotency.md)):

| Situation | Stored key | Response |
|---|---|---|
| Header present, new key | `client:<header value>` | `202 Accepted`, the new `PENDING` run, `Location` |
| No header | `auto:` + `sha256(org \| periodStart \| periodEnd \| leftSource \| rightSource \| currency)` | `202` if new; otherwise as below |
| Key exists, same request body | — | `200 OK`, the **existing** run in its current state (it may be `COMPLETED` or `FAILED`) |
| `client:` key exists, different request body | — | `422 idempotency-key-reused` |

- With no header, the same organization, period, sources and currency always map to the same run. **To deliberately re-run a period** (say, after a late gateway file), send a fresh `Idempotency-Key`. The new run gets its own matches; exception cases that are still active are re-observed, not duplicated (rule K3).
- The `client:` / `auto:` prefixes keep the two namespaces apart, so a client can't send a key that collides with a derived one.
- The run stores a `request_fingerprint` (the SHA-256 of the canonical request) to detect a reused key with a different body.
- **How this differs from the IETF `Idempotency-Key` header draft.** The draft treats the key as a *request-replay* mechanism: the server stores the response, replays it for a retry, returns `409` while the first request is in flight, and may expire keys. Here the key is the run's **business identity**: stored on the run, never expiring, and a repeat returns the run's *current* state, not a replay of the first response. The `422` for a reused key with a different body is borrowed from the draft. The in-flight `409` isn't needed, because the run row exists the moment the first request commits.

---

## 6. Concurrency control

Exception cases are edited by several people, so every update is guarded by optimistic locking ([ADR 0007](adr/0007-optimistic-locking-on-exceptions.md)):

- `GET /exceptions/{id}` and every successful write return **`ETag: "<version>"`** (a strong validator built from `exception_case.version`).
- `PATCH /exceptions/{id}`, `POST /exceptions/{id}/resolve` and `POST /exceptions/{id}/reopen` **require `If-Match: "<version>"`**.
  - Missing → `428 precondition-required`.
  - Doesn't match the current version → `409 stale-version`, with `currentVersion` in the body; the client re-reads and decides.
- Adding a note (`POST /exceptions/{id}/notes`) doesn't change the case row, so it needs no `If-Match`, and two people adding notes at once both succeed.

Strict HTTP says a failed `If-Match` is `412 Precondition Failed`. The project uses `409` to follow TASKS.md M7.8 and to keep every "someone else changed this" response the same status; ADR 0007 records `412` as the rejected alternative.

---

## 7. Synchronous vs asynchronous: `200` / `201` / `202`

| Endpoint | Status | Body / headers |
|---|---|---|
| `POST /imports` | `202 Accepted` | The batch (status `RECEIVED`), `Location: /api/v1/imports/{id}` |
| `POST /imports/{id}/retry` | `202 Accepted` | The batch (status `RECEIVED`) |
| `POST /reconciliation-runs` (new) | `202 Accepted` | The run (status `PENDING`), `Location` |
| `POST /reconciliation-runs` (idempotent repeat) | `200 OK` | The existing run |
| `POST /reconciliation-runs/{id}/retry` | `202 Accepted` | The run (status `PENDING`) |
| `POST /exceptions/{id}/notes` | `201 Created` | The note, `Location` |
| `PATCH /exceptions/{id}`, `POST …/resolve`, `POST …/reopen` | `200 OK` | The updated case, new `ETag` |
| `POST /members`, `POST /platform/organizations` | `201 Created` | The created resource, `Location` |
| `POST /auth/login`, `/auth/refresh`, `/auth/change-password` | `200 OK` | Tokens |
| `POST /auth/logout` | `204 No Content` | — |

**`202` means "accepted for processing, not done".** The file (or run request) and its job row are committed before the response, so the work can't be lost, but it hasn't happened yet. The client polls the `Location` URL until the status is terminal; non-terminal responses carry `Retry-After: 2` to say how often. **`201`** is used only when the resource is complete in its final form by the time the response is sent (a note, a member). ([ADR 0003](adr/0003-asynchronous-imports-and-runs.md).)

---

## 8. Endpoint reference

### 8.1 Index

Permissions are from [authentication.plan §5](../plans/authentication.plan), plus `RUN_READ` (§9). All org-scoped routes also return `401`, `403`, `404 organization-not-found` and `429` as described above.

| Method and path | Permission | Success | Main errors | Task |
|---|---|---|---|---|
| `POST /auth/login` | public | 200 | 401 `invalid-credentials`, 429 | M3.3b |
| `POST /auth/refresh` | refresh cookie | 200 | 401 | M3.3d |
| `POST /auth/logout` | refresh cookie | 204 | — | M3.3d |
| `POST /auth/change-password` | authenticated | 200 | 400 `password-policy`, 401 | M3.3g |
| `GET /members` | `MEMBER_MANAGE` | 200 page | — | M3.3g |
| `POST /members` | `MEMBER_MANAGE` | 201 | 400, 409 `already-a-member` | M3.3g |
| `PATCH /members/{userId}` | `MEMBER_MANAGE` | 200 | 404, 409 `last-org-admin` | M3.3g |
| `POST /members/{userId}/reset-password` | `MEMBER_MANAGE` | 200 | 404 | M3.3g |
| `POST /members/{userId}/disable` | `MEMBER_MANAGE` | 200 | 404, 409 `last-org-admin` | M3.3g |
| `GET /platform/organizations` | `PLATFORM_ORG_MANAGE` | 200 page | — | M3 |
| `POST /platform/organizations` | `PLATFORM_ORG_MANAGE` | 201 | 400 | M3 |
| `POST /imports` | `IMPORT_CREATE` | 202 | 400, 409 `duplicate-file`, 413, 415 | M5.7 |
| `GET /imports` | `IMPORT_READ` | 200 page | 400 | M5.18 |
| `GET /imports/{id}` | `IMPORT_READ` | 200 | 404 | M5.17 |
| `GET /imports/{id}/errors` | `IMPORT_READ` | 200 page | 404 | M5.17 |
| `POST /imports/{id}/retry` | `IMPORT_CREATE` | 202 | 404, 409 `invalid-transition` | M5.20 |
| `GET /transactions` | `TRANSACTION_READ` | 200 slice | 400 | M5.19 |
| `POST /reconciliation-runs` | `RUN_CREATE` | 202 / 200 | 400, 409 `import-in-progress`, 422 | M6.14 |
| `GET /reconciliation-runs` | `RUN_READ` | 200 page | 400 | M6.17 |
| `GET /reconciliation-runs/{id}` | `RUN_READ` | 200 | 404 | M6.15 |
| `GET /reconciliation-runs/{id}/matches` | `RUN_READ` | 200 page | 404 | M6.16 |
| `POST /reconciliation-runs/{id}/retry` | `RUN_CREATE` | 202 | 404, 409 `invalid-transition` | M6.12 |
| `GET /exceptions` | `EXCEPTION_READ` | 200 page | 400 | M7.6 |
| `GET /exceptions/{id}` | `EXCEPTION_READ` | 200 + `ETag` | 404 | M7.7 |
| `PATCH /exceptions/{id}` | `EXCEPTION_UPDATE` | 200 + `ETag` | 400, 404, 409, 422 `invalid-assignee`, 428 | M7.8 |
| `POST /exceptions/{id}/notes` | `EXCEPTION_UPDATE` | 201 | 400, 404 | M7.9 |
| `POST /exceptions/{id}/resolve` | `EXCEPTION_RESOLVE` | 200 + `ETag` | 400, 404, 409, 428 | M7.10 |
| `POST /exceptions/{id}/reopen` | `EXCEPTION_REOPEN` | 200 + `ETag` | 400, 404, 409 `active-case-exists`, 428 | M7.11 |
| `GET /audit-events?entityType=&entityId=` | `AUDIT_READ` | 200 page | 400 | M7.13 |
| `GET /reports/reconciliation?runId=` or `?from=&to=&currency=` | `REPORT_READ` + `EXCEPTION_READ` | 200 | 400, 404 | M8.1 |
| `GET /reports/import-quality` | `REPORT_READ` | 200 | 400 | M8.2 |
| `GET /reports/exception-ageing` | `REPORT_READ` | 200 | 400 | M8.3 |
| `GET /reports/exceptions/export?runId=` | `REPORT_READ` + `EXCEPTION_READ` | 200 `text/csv` | 404 | M8.4 |

All paths are under `/api/v1`. The detailed reference below starts with the import endpoints; each later milestone adds its section here as its endpoints land, so this document grows with the API.

### 8.2 `POST /api/v1/imports`

Upload one CSV file. Synchronous checks (size, type, header, duplicate content) happen before anything is stored; the rows are processed in the background.

**Request:** `multipart/form-data` with parts:

| Part | Type | Required | Notes |
|---|---|---|---|
| `file` | file | Yes | The CSV, ≤ 50 MB, contract in [requirements.md §7](requirements.md#7-csv-file-contracts) |
| `sourceType` | string | Yes | `INTERNAL_LEDGER` or `GATEWAY` |

```http
POST /api/v1/imports
Authorization: Bearer eyJ…
X-Organization-Id: 0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0e
Content-Type: multipart/form-data; boundary=----x

------x
Content-Disposition: form-data; name="sourceType"

GATEWAY
------x
Content-Disposition: form-data; name="file"; filename="gateway-basic.csv"
Content-Type: text/csv

merchant_reference,amount,currency,status,transaction_at,gateway_transaction_id
PAY-101,1000.00,aed,captured,2026-10-01T05:15:00Z,gw_0001
…
------x--
```

**Response `202 Accepted`:**

```http
HTTP/1.1 202 Accepted
Location: /api/v1/imports/01933b0e-4a2c-7d1e-9b3f-6c7d8e9f0a1b
Retry-After: 2

{
  "id": "01933b0e-4a2c-7d1e-9b3f-6c7d8e9f0a1b",
  "sourceType": "GATEWAY",
  "status": "RECEIVED",
  "fileName": "gateway-basic.csv",
  "sizeBytes": 479,
  "totalRows": 0, "validRows": 0, "errorRows": 0, "storedErrors": 0,
  "failureReason": null,
  "uploadedBy": "0193b9e2-1111-7a10-8d2e-000000000001",
  "createdAt": "2026-11-16T09:02:11Z", "startedAt": null, "completedAt": null
}
```

**Errors:** `400 empty-file`, `400 missing-columns` (`missingColumns`), `400 duplicate-columns` (`duplicateColumns`), `400 invalid-encoding`, `400 validation-failed` (missing or unknown `sourceType`), `409 duplicate-file` (`existingBatchId`), `413 file-too-large`, `415 unsupported-media-type` (also for a request that isn't `multipart/form-data`). On any of these, no batch, job, stored file or audit event is created.

`413` is checked in code as well as by the container; Tomcat's `max-swallow-size` (100 MB) is set so a client sending up to twice the limit reads the `413` instead of seeing a connection reset.

The original filename is stored for display only. It is user input, so it's never used in a storage key or a path.

### 8.3 `GET /api/v1/imports/{id}`

Batch status and counts; cheap to poll.

**Response `200 OK`:** the same shape as the upload response, with current values. `storedErrors` can be lower than the number of row errors when a file has more than 10,000 errors: every bad row is counted in `errorRows`, but only the first 10,000 errors are stored. Non-terminal statuses (`RECEIVED`, `PROCESSING`) carry `Retry-After: 2`.

```json
{
  "id": "01933b0e-4a2c-7d1e-9b3f-6c7d8e9f0a1b",
  "sourceType": "INTERNAL_LEDGER",
  "status": "COMPLETED_WITH_ERRORS",
  "fileName": "ledger-malformed.csv",
  "sizeBytes": 386,
  "totalRows": 9, "validRows": 1, "errorRows": 8, "storedErrors": 8,
  "failureReason": null,
  "uploadedBy": "0193b9e2-1111-7a10-8d2e-000000000001",
  "createdAt": "2026-11-16T09:02:11Z",
  "startedAt": "2026-11-16T09:02:12Z",
  "completedAt": "2026-11-16T09:02:12Z"
}
```

**Errors:** `404 not-found` (unknown, or another tenant's batch).

### 8.4 `GET /api/v1/imports/{id}/errors`

The batch's row errors, paginated, ordered by row number then column.

**Response `200 OK`:**

```json
{
  "content": [
    { "rowNumber": 2, "column": "amount", "code": "INVALID_AMOUNT",
      "message": "Amount '1,000.00' is not a plain decimal number", "rawRow": "PAY-402,\"1,000.00\",AED,SUCCESS,2026-10-05" },
    { "rowNumber": 9, "column": null, "code": "MALFORMED_ROW",
      "message": "Quoted field is never closed", "rawRow": "PAY-409,\"60.00,AED,SUCCESS,2026-10-05" }
  ],
  "page": { "number": 0, "size": 50, "totalElements": 8, "totalPages": 1 }
}
```

**Errors:** `404 not-found`.

### 8.5 `GET /api/v1/imports`

The organization's batches, newest first. Filters: `status` (repeatable), `sourceType`. Sort: `createdAt` (default `desc`). Response: a page of the batch shape from §8.2.

### 8.6 `POST /api/v1/imports/{id}/retry`

Reprocess a `FAILED` batch from scratch, with an audited reason. The stored file is reused; nothing is uploaded.

**Request:** `{ "reason": "Storage outage during the first attempt" }` (required, non-blank).

**Response `202 Accepted`:** the batch, now `RECEIVED`, with counters reset, plus `Location` and `Retry-After: 2`. In the same transaction the batch's records and row errors are deleted and a new job is queued; the old job stays `FAILED` for history. The audit event `IMPORT_RETRY_REQUESTED` carries the reason and the previous failure reason.

**Errors:** `400 validation-failed` (no reason), `404 not-found`, `409 invalid-transition` (the batch isn't `FAILED`; `currentStatus` in the body).

### 8.7 `GET /api/v1/transactions`

Search the organization's normalized records (M5.19). Only records of accepted batches (`COMPLETED`, `COMPLETED_WITH_ERRORS`; rule P3) are returned.

| Parameter | Required | Notes |
|---|---|---|
| `transactionDateFrom`, `transactionDateTo` | Yes | `YYYY-MM-DD`, inclusive, `to ≥ from`, at most 366 days: every query prunes to the months in range |
| `sourceType` | No | `INTERNAL_LEDGER` or `GATEWAY` |
| `reference` | No | Normalized with rules N1–N3 before comparing, so `pay 103` finds `PAY 103` (but not `PAY-103`, rule N4) |
| `currency` | No | Case-insensitive |
| `batchId` | No | Another organization's batch matches nothing |
| `sort` | No | `transactionDate` (default, ascending), `amount`, `createdAt`; `id` breaks ties. Anything else is `400 validation-failed` |
| `page`, `size` | No | As §4; `size` is clamped to 200 |

**Response `200 OK`:** a slice (§4), so no total count:

```json
{
  "content": [
    { "id": "…", "batchId": "…", "sourceType": "GATEWAY", "sourceRowNumber": 3,
      "rawReference": "PAY-104", "normalizedReference": "PAY-104",
      "amount": "250.0000", "currency": "AED", "transactionType": "PAYMENT", "status": "SUCCESS",
      "transactionDate": "2026-10-02", "transactionAt": "2026-10-02T05:00:00Z",
      "duplicateInSource": true, "createdAt": "2026-11-16T09:02:12Z" }
  ],
  "page": { "number": 0, "size": 50, "hasNext": false }
}
```

`raw_values` is not part of the list (it's the largest column). **Errors:** `400 validation-failed` (missing or reversed range, range over 366 days, unknown sort field, bad enum or UUID).

### 8.8 `POST /api/v1/reconciliation-runs`

Request a reconciliation run for one period, currency and source pair (M6.11, M6.14). The run is processed in the background, one anchor day at a time ([ADR 0012](adr/0012-set-based-matching-in-sql.md)).

**Request:**

```http
POST /api/v1/reconciliation-runs
Idempotency-Key: 7f9c2a10-3d1e-4b4f-9f0e-1c8a7b6d5e4f
X-Organization-Id: 0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0e
Content-Type: application/json

{ "periodStart": "2026-10-01", "periodEnd": "2026-10-31", "currency": "AED" }
```

| Field | Required | Rule |
|---|---|---|
| `periodStart`, `periodEnd` | Yes | `YYYY-MM-DD`, both inclusive; `periodEnd ≥ periodStart`; at most 92 days |
| `currency` | Yes | An ISO-4217 code; trimmed and upper-cased first, so `aed` is `AED` (and the same run) |
| `leftSource`, `rightSource` | No | Default `INTERNAL_LEDGER` / `GATEWAY`; must differ |
| `Idempotency-Key` header | No | 1–128 printable ASCII characters (§5) |

**Response `202 Accepted`** (a new run) with `Location: /api/v1/reconciliation-runs/{id}` and `Retry-After: 2`; the body is the run shape of §8.10, `status` `PENDING`, `summary` `null`. The run, its job and the `RUN_REQUESTED` audit event commit in one transaction.

**Response `200 OK`** (an idempotent repeat, §5): the existing run, in whatever state it is in, with `Location`; `Retry-After: 2` while it isn't terminal. A repeat is answered even while an import is in progress, because it starts nothing.

**Errors:** `400 validation-failed` (each rule above; `errors[]` names the field, e.g. `must-not-be-before-start`, `period-too-long`, `invalid-currency`, `sources-must-differ`, `invalid-idempotency-key`), `409 import-in-progress` (an import of the organization is `RECEIVED` or `PROCESSING`), `409 run-in-progress` (another run of the same organization, period, sources and currency is `PENDING` or `RUNNING`, `existingRunId`; enforced by a partial unique index, so even racing requests with different keys can't both start one), `422 idempotency-key-reused`. On any error, nothing is created.

### 8.9 `POST /api/v1/reconciliation-runs/{id}/retry`

Retry a `FAILED` run (M6.12), with an audited reason.

**Request:** `{ "reason": "Database restarted during the first attempt" }` (required, non-blank, ≤ 1,000 characters).

**Response `202 Accepted`:** the run, now `PENDING`, `failureReason` cleared, plus `Location` and `Retry-After: 2`. The run **keeps** `progress.lastCompletedDate`: the new job resumes after the last committed day, so days already done are never redone or duplicated. A new job is queued in the same transaction; the old one stays `FAILED` for history. The audit event `RUN_RETRY_REQUESTED` carries the reason and the previous failure reason.

**Errors:** `400 validation-failed` (no reason), `404 not-found`, `409 invalid-transition` (the run isn't `FAILED`; `currentStatus`), `409 run-in-progress` (another run of the same scope became active meanwhile).

### 8.10 `GET /api/v1/reconciliation-runs/{id}`

A run's status, progress and, once `COMPLETED`, its summary (M6.15). Cheap to poll: one primary-key lookup. Non-terminal runs (`PENDING`, `RUNNING`) carry `Retry-After: 2`.

```json
{
  "id": "0193c0a1-…",
  "status": "COMPLETED",
  "periodStart": "2026-10-01", "periodEnd": "2026-10-02",
  "currency": "AED", "leftSource": "INTERNAL_LEDGER", "rightSource": "GATEWAY",
  "progress": { "lastCompletedDate": "2026-10-02" },
  "summary": {
    "matched":            { "count": 3, "amount": "1420.5000" },
    "amountMismatch":     { "count": 1, "netDifference": "-50.0000" },
    "unmatchedLeft":      { "count": 1, "amount": "500.0000" },
    "unmatchedRight":     { "count": 1, "amount": "200.0000" },
    "duplicates":         { "count": 1 },
    "currencyMismatches": { "count": 0 },
    "excluded":           { "count": 1 }
  },
  "failureReason": null,
  "triggeredBy": "0193b9e2-1111-7a10-8d2e-000000000001",
  "createdAt": "2026-11-16T09:12:44Z", "startedAt": "2026-11-16T09:12:45Z", "completedAt": "2026-11-16T09:12:45Z"
}
```

- `summary` is `null` until `COMPLETED`; it is computed once, from scratch, in the completing transaction, so a resumed or retried run shows exactly what a clean run would.
- Counts are **cases**, not records (a duplicate case linking three records counts once), except `excluded`, which counts **records** that failed rule P4 or P5 ([reconciliation-rules.md §2](reconciliation-rules.md#2-which-records-take-part-in-a-run)).
- `unmatchedLeft` / `unmatchedRight` follow the run's sources: with the defaults, left is the ledger, so `unmatchedLeft` is `MISSING_IN_GATEWAY`. Their `amount` is the sum of the cases' `amount`.
- `amountMismatch.netDifference` is the sum of `gateway − ledger` over the run's amount-mismatch cases.
- `progress.lastCompletedDate` is the resume checkpoint: the last day whose matches and cases are committed.
- `failureReason` is a short code (`internal-error`, `retries-exhausted`, `engine-invariant-violated: …`), never a stack trace.

**Errors:** `404 not-found`.

### 8.11 `GET /api/v1/reconciliation-runs/{id}/matches`

The run's matches, paginated (§4: `page`, `size` default 50, clamped to 200), sorted by the ledger record's normalized reference, then the match id. One query joins both records on `(id, transaction_date)`, so each lookup prunes to one partition.

```json
{
  "content": [
    { "id": "0193c0a2-…", "matchType": "EXACT", "currency": "AED", "amountDifference": "0.0000",
      "ledger":  { "id": "…", "reference": " pay-106 ", "normalizedReference": "PAY-106", "amount": "120.5000", "transactionDate": "2026-10-01" },
      "gateway": { "id": "…", "reference": "PAY-106",   "normalizedReference": "PAY-106", "amount": "120.5000", "transactionDate": "2026-10-01" } }
  ],
  "page": { "number": 0, "size": 50, "totalElements": 3, "totalPages": 1 }
}
```

`reference` is the raw reference exactly as in the file; `normalizedReference` is what was matched on. **Errors:** `404 not-found` for an unknown run or another tenant's (the run is checked first, so it is never an empty page).

### 8.13 Notes for later sections

Decisions already made that the later endpoint sections must reflect:

- **Run summary** (`GET /reconciliation-runs/{id}`): `summary` is `null` until `COMPLETED`. Counts are **cases**, not records: a duplicate case linking three records counts once. `unmatchedLeft` / `unmatchedRight` follow the run's sources (with the defaults, left = ledger, so `unmatchedLeft` is `MISSING_IN_GATEWAY`).
- **Exception `amount`** is the amount at stake: the ledger amount when the case has a ledger record, otherwise the gateway amount. For a duplicate it's the smallest such amount, a value for filtering, not a total ([reconciliation-rules.md §5.1](reconciliation-rules.md#51-exception-types)).
- **Exception detail** shows every linked record side by side, including all records of a duplicate case, which may span several days and both sources.
- **`GET /transactions`** requires `transactionDateFrom` and `transactionDateTo` (at most 366 days apart) so every query prunes partitions, returns a slice, and only shows records from accepted batches (rule P3). `reference` is normalized with rules N1–N3 before comparing.
- **Run request validation:** `periodStart`, `periodEnd` required, `periodEnd ≥ periodStart`, at most 92 days; `currency` a valid ISO-4217 code; `leftSource` / `rightSource` default to `INTERNAL_LEDGER` / `GATEWAY` and must differ.

---

## 9. Authorization matrix

The matrix as built in M3.7. Code checks **permissions**, never role names, so changing what a role can do is a one-line change in `security.authz.RolePermissions`, the only place roles become permissions.

How it is enforced and kept honest:

- Every controller method carries `@RequiresPermission(Permission.X)`, a meta-annotation for `@PreAuthorize("hasAuthority('X')")`. The authorities of a request are the caller's permissions in the selected organization, resolved per request by `TenantContextFilter`. An architecture rule fails the build if a controller method outside `security.auth` has no permission annotation.
- `RolePermissionsTest` pins this table cell by cell; `AuthorizationMatrixIT` calls every endpoint as every role and expects `2xx` or `403 forbidden` accordingly.
- `PermissionDocsTest` checks the endpoint index in §8.1 against the code: every mapped endpoint must have a row, and the row must name the permission its annotation requires.

| Permission | VIEWER | UPLOADER | INVESTIGATOR | ORG_ADMIN |
|---|---|---|---|---|
| `IMPORT_READ`, `TRANSACTION_READ`, `REPORT_READ`, `EXCEPTION_READ`, `RUN_READ` | ✓ | ✓ | ✓ | ✓ |
| `IMPORT_CREATE` (upload, retry import) | | ✓ | | ✓ |
| `RUN_CREATE` (start, retry run) | | ✓ | ✓ | ✓ |
| `EXCEPTION_UPDATE` (assign, review, notes) | | | ✓ | ✓ |
| `EXCEPTION_RESOLVE` | | | ✓ | ✓ |
| `EXCEPTION_REOPEN` | | | | ✓ |
| `MEMBER_MANAGE` | | | | ✓ |
| `AUDIT_READ` | | | | ✓ |

`RUN_READ` isn't in authentication.plan §5 yet: phase-M6 adds it (granted to every role) because there was no read permission for runs. `PLATFORM_ORG_MANAGE` is separate and disjoint: it never satisfies an org-scoped permission.
