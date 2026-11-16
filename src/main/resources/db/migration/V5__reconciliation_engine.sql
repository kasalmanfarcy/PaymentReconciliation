-- =====================================================================
-- ReconFlow — reconciliation engine support (M6)
--
-- What: the columns, table, function and indexes the matching engine
-- needs on top of V1 (phase-M6 §3, database-design.md §7).
-- Backfill: none. reconciliation_run and exception_case are empty until
-- M6 ships (nothing wrote them before), so the NOT NULL columns without
-- a default need no backfill.
-- =====================================================================

-- 1. Summary columns V1 lacks, and the request fingerprint.
--    currency_mismatch_count: the fifth exception type's count.
--    excluded_count: records of the run's organization, sources and
--      period that fail P4 (not SUCCESS) or P5 (group has no record in
--      the run's currency). reconciliation-rules.md §4 says excluded
--      records are counted, never silently dropped; the invariant needs it.
--    request_fingerprint: SHA-256 hex of the canonical request, to detect
--      an Idempotency-Key reused with a different body (ADR 0006).
ALTER TABLE reconciliation_run
    ADD COLUMN currency_mismatch_count INT      NOT NULL DEFAULT 0,
    ADD COLUMN excluded_count          INT      NOT NULL DEFAULT 0,
    ADD COLUMN request_fingerprint     CHAR(64) NOT NULL;

-- At most one active (PENDING or RUNNING) run per organization and scope
-- (period, sources, currency), enforced by the database: two requests
-- with different Idempotency-Keys for the same scope, racing, can't both
-- start one. Different keys are how a period is re-run on purpose (ADR
-- 0006), which stays possible once the active run has finished.
CREATE UNIQUE INDEX reconciliation_run_active_scope_uk ON reconciliation_run
    (organization_id, currency, left_source, right_source, period_start, period_end)
    WHERE status IN ('PENDING', 'RUNNING');

-- 2. The amount at stake in a case: the ledger amount if the case has a
--    ledger record, otherwise the gateway amount (the smallest one, for a
--    duplicate). Drives amount filters (M7.6) and run totals.
ALTER TABLE exception_case
    ADD COLUMN amount NUMERIC(19,4) NOT NULL;

-- 3. Every run that observed a case, whether it created it or found it
--    already active (rule K3). exception_case.run_id stays "the run that
--    raised it". Owned by the exceptions module; written only by
--    DiscrepancyRecorder.
CREATE TABLE exception_case_run (
    exception_id     UUID        NOT NULL REFERENCES exception_case (id),
    run_id           UUID        NOT NULL REFERENCES reconciliation_run (id),
    organization_id  UUID        NOT NULL REFERENCES organization (id),
    created          BOOLEAN     NOT NULL,     -- true if this run created the case
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, exception_id)
);
CREATE INDEX exception_case_run_exception_idx ON exception_case_run (exception_id);

-- Tenant-owned, so row-level security like every other such table (V4).
ALTER TABLE exception_case_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE exception_case_run FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exception_case_run
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

-- 4. UUIDv7 inside the database, for set-based INSERT ... SELECT (ADR 0010;
--    PostgreSQL 16 has no uuidv7(), it arrives in 18).
--    gen_random_uuid() gives a v4 UUID (random, correct variant bits).
--    Overwrite the first 48 bits with the Unix time in milliseconds, then
--    set bits 52 and 53 (set_bit counts from the right within each byte,
--    so these are the 0x10 and 0x20 bits of byte 6): the version nibble
--    goes from 0100 (4) to 0111 (7). Same layout as the Java generator.
CREATE FUNCTION uuid_generate_v7() RETURNS uuid
LANGUAGE sql VOLATILE AS $$
    SELECT encode(
        set_bit(
            set_bit(
                overlay(uuid_send(gen_random_uuid())
                        PLACING substring(int8send(floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint) FROM 3)
                        FROM 1 FOR 6),
                52, 1),
            53, 1),
        'hex')::uuid;
$$;

-- 5. The engine looks a group up by reference across the whole run
--    period. transaction_record_match_idx leads with source and date, so a
--    reference lookup over 31 days can't use it well. Created on the
--    partitioned parent, so every partition gets it.
CREATE INDEX transaction_record_reference_idx ON transaction_record
    (organization_id, normalized_reference, transaction_type, transaction_date);
