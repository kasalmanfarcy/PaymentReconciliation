# 0003. Imports and runs are asynchronous and return 202 Accepted

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M5.7, M6.14, M9.2

## Context

The README asked: sync or async imports in v1, and `201 Created` or `202 Accepted`?

- A 100k-row file takes roughly 10–30 seconds to parse, validate and insert (scalability.md §2.3); the limit is 500,000 rows. A month-long run for a large tenant compares millions of records.
- Holding an HTTP request open that long is fragile: load balancers and clients time out (often at 30–60 s), a retried request starts the work again, and a busy API instance has its threads tied up exactly during the 09:00 upload burst.
- A crash mid-request loses the work and leaves the client unsure whether anything happened.

## Decision

We will make **file imports and reconciliation runs asynchronous from v1**:

1. The API does the cheap, synchronous checks (authentication, permissions, size, type, CSV header, duplicate content; or request validation and idempotency for runs).
2. It durably records the request (the stored file, the `import_batch` or `reconciliation_run` row, and a `job` row, the database rows in one transaction).
3. It returns **`202 Accepted`** with the resource in its initial state (`RECEIVED` / `PENDING`) and a `Location` header pointing at it.
4. A worker process does the work (ADR 0004). The client polls the `Location` URL until the status is terminal; non-terminal responses carry `Retry-After: 2`.

Retries of failed batches and runs follow the same pattern. Everything that completes in the request (notes, members) returns `201 Created`.

## Consequences

- No request holds a thread for more than the synchronous checks, so the API stays fast during upload bursts.
- Because the job row commits with the business row, accepted work can't be lost; a crash just delays it until a worker (or the sweeper) picks it up.
- Clients must poll (or, later, subscribe). The status endpoints must be cheap: they're single-row lookups by primary key.
- Errors split in two: file-level problems are immediate `4xx` responses; row-level problems appear later in the batch's error list. The API docs have to explain both.
- A worker process and a job queue are needed from v1, not as a later optimization (moved from M9.2 into M5).

## Alternatives considered

- **Synchronous processing with `201 Created`.** Simple for small files, but too long and too fragile for large ones, and `201` would claim the resource is complete when it isn't. Rejected.
- **Synchronous below a size threshold, asynchronous above it.** Two code paths and two response contracts for one operation; clients must handle both anyway. Rejected.
- **`@Async` in the API process.** Returns quickly, but the work lives in memory: a restart loses it, and it still competes with API threads. Rejected (see ADR 0004).
- **Server-sent events or webhooks instead of polling.** Nicer for clients, but more infrastructure; polling a cheap endpoint is enough for v1. Possible later addition.
