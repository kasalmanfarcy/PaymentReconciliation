# 0007. Optimistic locking on exceptions: @Version, ETag/If-Match, 409 on a stale version

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M7.1, M7.8, M7.10, M7.11, M7.14

## Context

The README's working assumption was optimistic locking (`@Version` + `409`). Several investigators work the same queue. Without protection, the lost-update problem happens: Alice opens PAY-103 and starts typing a resolution; Bob opens it, assigns it to himself and moves it to review; Alice saves, and her write silently overwrites Bob's. In a financial workflow, a silent overwrite is an audit failure.

Edits happen over HTTP with human "think time" of seconds to minutes between reading and writing. V1 has `exception_case.version BIGINT`.

## Decision

We will use **optimistic locking**:

- `exception_case.version` is mapped with JPA `@Version`; every write increments it.
- Every response with a case carries **`ETag: "<version>"`**.
- `PATCH /exceptions/{id}`, `POST …/resolve` and `POST …/reopen` **require `If-Match: "<version>"`**.
  - Missing `If-Match` → **`428 precondition-required`**. Requiring the header means a client can't skip the check by forgetting it.
  - `If-Match` older than the current version → **`409 stale-version`** with `currentVersion`. The client re-reads and decides.
- The service compares `If-Match` with the loaded version explicitly (catches the slow race), and the `UPDATE … WHERE version = :expected` from `@Version` catches the fast race between two in-flight requests. Both map to the same `409`.
- Adding a note doesn't modify the case row, so it needs no `If-Match`.

## Consequences

- No locks are held between requests, so a user who opens a case and goes to lunch blocks nobody.
- Concurrent edits are detected, never silently merged: exactly one of two simultaneous resolves wins (M7.14).
- Clients must keep the `ETag` and handle `409` by re-reading. A UI shows "this case changed while you were editing".
- Under heavy contention on one case, users retry more. Cases are low-contention (a few people per case), so this is fine; load scenario S6 checks it.

## Alternatives considered

- **Pessimistic locking (`SELECT … FOR UPDATE`) across the edit.** Would hold a database lock across user think time, which is impossible over stateless HTTP and would block other users. Rejected. (Short `FOR UPDATE` locks *within* one request are still used where needed, e.g. job claims.)
- **Last write wins.** Simple and silently loses updates. Rejected.
- **`412 Precondition Failed` instead of `409`.** The status HTTP prescribes for a failed `If-Match`, and more correct strictly speaking. We chose `409` to match TASKS.md M7.8 and to give every "someone else changed this" situation one status. If the API is ever published for third parties, `412` is the better choice; it's a small, contained change.
- **Version in the request body instead of headers.** Works, but mixes concurrency metadata into the resource and ignores the standard HTTP mechanism that caches and tools understand. Rejected.
