-- One summary row per run and anchor day (M8.1), in the chunk's transaction,
-- next to the day's matches, cases and checkpoint. Plain INSERT: the
-- checkpoint makes a second row for the same day impossible, so a primary-key
-- violation here is a bug and must fail the chunk, not be skipped.
INSERT INTO reconciliation_run_day
    (run_id, organization_id, day, currency,
     matched_count, matched_amount, mismatch_count, mismatch_net_difference,
     unmatched_left_count, unmatched_left_amount, unmatched_right_count, unmatched_right_amount,
     duplicate_count, currency_mismatch_count)
VALUES (:runId, :org, :day, :currency,
        :matchedCount, :matchedAmount, :mismatchCount, :mismatchNetDifference,
        :unmatchedLeftCount, :unmatchedLeftAmount, :unmatchedRightCount, :unmatchedRightAmount,
        :duplicateCount, :currencyMismatchCount)
