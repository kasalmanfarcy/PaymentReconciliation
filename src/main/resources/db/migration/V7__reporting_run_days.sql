-- =====================================================================
-- ReconFlow — per-day run results for date-range reports (M8.1)
--
-- What: one summary row per run per reconciled (anchor) day, and the
-- anchor day on each "run observed case" link, both written by the
-- engine in the same transaction as that day's matches, cases and
-- checkpoint (phase-M8 §2.3, §M8.1).
--
-- Why: runs may overlap (1-7 Oct, then 5-10 Oct, or a re-run with a new
-- Idempotency-Key). A date-range report that added up whole-run totals
-- would count the overlapping days two or three times. With results per
-- day, the report picks one authoritative run per day (the most
-- recently completed one) and sums only those rows.
--
-- Backfill: none. Runs completed before V7 have no day rows and no day
-- on their case links; date-range mode reports their days as not
-- covered (coverage.missingDays), never as zero activity. Run mode reads
-- the run row's own columns, so it is unaffected.
-- =====================================================================

-- 1. Owned by reconciliation, written only by the engine's chunk
--    transaction. The columns mirror reconciliation_run's summary
--    columns (counts are cases, not records), except excluded_count:
--    excluded records are counted over the whole period at completion
--    and have no anchor day.
CREATE TABLE reconciliation_run_day (
    run_id                   UUID          NOT NULL REFERENCES reconciliation_run (id),
    organization_id          UUID          NOT NULL REFERENCES organization (id),
    day                      DATE          NOT NULL,
    currency                 CHAR(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    matched_count            INT           NOT NULL CHECK (matched_count >= 0),
    matched_amount           NUMERIC(19,4) NOT NULL,
    mismatch_count           INT           NOT NULL CHECK (mismatch_count >= 0),
    mismatch_net_difference  NUMERIC(19,4) NOT NULL,
    unmatched_left_count     INT           NOT NULL CHECK (unmatched_left_count >= 0),
    unmatched_left_amount    NUMERIC(19,4) NOT NULL,
    unmatched_right_count    INT           NOT NULL CHECK (unmatched_right_count >= 0),
    unmatched_right_amount   NUMERIC(19,4) NOT NULL,
    duplicate_count          INT           NOT NULL CHECK (duplicate_count >= 0),
    currency_mismatch_count  INT           NOT NULL CHECK (currency_mismatch_count >= 0),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- A chunk and its day row commit together with the checkpoint, so a
    -- second row for the same (run, day) is a bug: fail loudly, never
    -- ON CONFLICT DO NOTHING.
    PRIMARY KEY (run_id, day)
);

-- The date-range report: "every day row of this organization and
-- currency in [from, to]", then the latest completed run per day.
CREATE INDEX reconciliation_run_day_lookup_idx
    ON reconciliation_run_day (organization_id, currency, day);

ALTER TABLE reconciliation_run_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_run_day FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reconciliation_run_day
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

-- 2. The anchor day on which a run observed a case. A case is observed
--    at most once per run (one group, one anchor day), so with this the
--    date-range report attributes each case to the same (run, day) as
--    the day row that counted it. Nullable: links written before V7
--    have no day.
ALTER TABLE exception_case_run
    ADD COLUMN day DATE;
