# 0006. A run's identity is its idempotency key; cases are deduplicated by discrepancy key

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M6.3, M6.11, M6.14, M6.20, M7.3

## Context

The README asked: what makes two reconciliation runs "the same run"?

Clients retry on timeouts and users double-click "Start". If each request creates a run, two runs over the same period both create matches and both raise every exception, and the reports double-count. On the other hand, users legitimately need to run a period again (a late gateway file arrived).

V1 has `reconciliation_run UNIQUE (organization_id, idempotency_key)` and `exception_case_active_uk UNIQUE (organization_id, discrepancy_key) WHERE status <> 'RESOLVED'`.

## Decision

**Run identity.** `POST /reconciliation-runs` accepts an optional `Idempotency-Key` header (1–128 printable ASCII characters):

- With a header, the stored key is `client:<value>`.
- Without one, the stored key is `auto:` + `sha256(organization | period_start | period_end | left_source | right_source | currency)`. So "the same run" means the same organization, period, sources and currency.
- The run also stores a `request_fingerprint`, the SHA-256 of the canonical request.
- Insert with `ON CONFLICT (organization_id, idempotency_key) DO NOTHING`. New → `202 Accepted`. Existing with the same fingerprint → **`200 OK` with the existing run** in whatever state it's in. Existing `client:` key with a different fingerprint → `422 idempotency-key-reused`.
- To re-run a period on purpose, send a fresh key.

**Exception identity across runs.** Each case has a deterministic `discrepancy_key` (`<TYPE>:<reference>:<transaction_type>:<sorted currencies>`, reconciliation-rules.md §6) with no run ID or period in it. A run that finds an active case with its key links to it instead of creating another; the partial unique index guarantees at most one active case per key, even under concurrent runs.

## Consequences

- Retries and double-clicks are harmless; concurrent identical requests produce one run (one `202`, one `200`).
- A deliberate re-run gets its own matches but doesn't duplicate active cases, so the exception queue stays clean.
- The key never expires: a request for the same period a month later returns the old run. That is the intended behaviour (the run *is* the answer for that period), but it surprises people; the API docs say "send a fresh key to re-run".
- A resolved case whose discrepancy appears again gets a new case (the partial index ignores resolved rows). Deliberate: the problem still exists.
- Two independent runs over overlapping periods can both hold matches for the same records. Reports over a date range must choose one authoritative run per day (M8).

## Alternatives considered

- **Always create a new run.** Duplicates matches and, without the discrepancy key, cases. Rejected.
- **Supersede the previous run** for the same period (mark it superseded, move or delete its results). More correct for "re-run after new data", but needs rules for in-flight runs, reports and cases linked to the old run. Kept as a v2 option.
- **Use the IETF Idempotency-Key semantics exactly** (store and replay the first response, expire keys, `409` while in flight). Designed for request replay, not for business identity; replaying "PENDING" when the run has long completed would mislead. We borrow only the `422` for a reused key with a different body.
- **Include the run ID or period in the discrepancy key.** Every run would raise a new case for the same problem. Rejected.
