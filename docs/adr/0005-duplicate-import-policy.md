# 0005. Reject duplicate files by content hash; retry failed batches in place

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M5.4, M5.9, M5.20, M5.23

## Context

The README asked: content hash, natural key, or both? And what does a deliberate re-import look like?

Users upload the same file twice: a double click, a client retry after a timeout, or simply forgetting. If a second copy is imported, every payment in it exists twice, and the engine (correctly) reports every one of them as `DUPLICATE_IN_SOURCE`: hundreds of false exceptions. Meanwhile, a batch that failed half-way (storage outage, a bug) needs some way to be processed again.

V1 already has `import_batch_content_uk UNIQUE (organization_id, source_type, content_hash)`.

## Decision

We will **identify a file by the SHA-256 of its bytes**, per organization and source type, and **reject a duplicate**:

- At upload, compute the hash while streaming the file. If a batch with the same `(organization_id, source_type, content_hash)` exists, return **`409 duplicate-file`** with `existingBatchId`, before storing anything.
- The unique constraint backs this up: if two identical uploads race, one insert wins, the other request deletes the object it just stored and returns the same `409` with the winner's ID.
- **A failed batch is reprocessed in place** with `POST /imports/{id}/retry` (with a reason, audited). The stored original file is reused; it's never re-uploaded. The retry deletes the batch's partial records and row errors and starts again from row 1, in one transaction.
- **Replacing a completed import** (a corrected file for the same day) is **out of v1 scope**. A corrected file has different bytes, so it *is* accepted as a new batch; its overlapping references then surface as duplicates for an investigator to resolve (`DUPLICATE_FILE_ARTIFACT`), which is the honest outcome without a supersede model.
- Uploads take no `Idempotency-Key`: the content hash is their natural idempotency key, and a retrying client gets back exactly the batch ID it was trying to create.

## Consequences

- One file, one batch, one audit trail. No question of which of two identical batches is authoritative.
- Client retries are safe and informative.
- A file that differs by a single byte (one extra row, a different line ending) is a different file. Overlap between different files isn't caught here; the engine catches it as duplicates (rule D2), loudly. That's deliberate: silently deduplicating across files could hide a real double charge.
- Deliberate re-imports of a completed batch aren't supported in v1. Users who need to correct data must resolve the resulting cases. Recorded as a v2 feature.

## Alternatives considered

- **Natural key per row only** (e.g. unique `(organization_id, source_type, normalized_reference, transaction_type, amount, transaction_date)` across all batches). Catches overlapping files too, but can't tell a deliberate correction or a genuine repeated payment from an accidental re-upload, and silently drops rows, which is worse than flagging them. Rejected.
- **Content hash and natural key together.** Inherits the natural key's problems. Rejected for v1.
- **Allow re-import with `replace=true`.** Needs a supersede model: mark the old batch's records inactive, and decide what happens to matches and cases that referenced them (re-run? keep? link?). That's a feature with its own rules doc and ADR. Deferred to v2.
- **Accept duplicates and let the engine flag them.** Every re-upload would raise one case per payment. Rejected.
