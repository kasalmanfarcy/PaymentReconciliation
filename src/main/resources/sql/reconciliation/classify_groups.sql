-- Classify every group anchored on :day (ADR 0012, reconciliation-rules.md §2-§4).
--
-- A group is (normalized_reference, transaction_type). A record takes part
-- when it passes P1 (organization), P2 (one of the run's sources), P3 (its
-- batch is accepted), P4 (SUCCESS) and P6 (in the period); P5 is applied to
-- the whole group at the end (it has a record in the run's currency).
--
-- Anchor-day chunking: a group's anchor is the earliest date among its
-- participating records. The chunk for :day finds every group with a
-- participating record on :day, loads ALL of each group's participating
-- records across the period, and keeps only the groups whose anchor is
-- :day. Every group therefore has exactly one anchor and is processed
-- exactly once, in full: cross-day matches, duplicates spread over several
-- days and gateway-only groups need no special cases.
INSERT INTO recon_group (normalized_reference, transaction_type, currencies, ledger_n, gateway_n,
                         ledger_ids, ledger_dates, gateway_ids, gateway_dates,
                         ledger_amount, gateway_amount, same_amount, outcome)
WITH day_keys AS (
    -- Groups with at least one participating record on :day (any currency).
    SELECT DISTINCT t.normalized_reference, t.transaction_type
      FROM transaction_record t
      JOIN import_batch b ON b.id = t.batch_id AND b.organization_id = t.organization_id
     WHERE t.organization_id  = :org
       AND t.transaction_date = :day
       AND t.source_type IN (:leftSource, :rightSource)
       AND t.status           = 'SUCCESS'
       AND b.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
),
candidates AS (
    -- Every participating record of those groups, both sides, any currency, the whole period.
    -- Served by transaction_record_reference_idx (organization_id, normalized_reference,
    -- transaction_type, transaction_date), pruned to the months of the period.
    SELECT t.id, t.transaction_date, t.source_type, t.normalized_reference, t.transaction_type,
           t.currency::text AS currency,   -- CHAR(3) is bpchar: compare and aggregate it as text
           t.amount, t.is_duplicate_in_source
      FROM day_keys k
      JOIN transaction_record t
        ON t.organization_id      = :org
       AND t.normalized_reference = k.normalized_reference
       AND t.transaction_type     = k.transaction_type
      JOIN import_batch b ON b.id = t.batch_id AND b.organization_id = t.organization_id
     WHERE t.transaction_date BETWEEN :periodStart AND :periodEnd
       AND t.source_type IN (:leftSource, :rightSource)
       AND t.status = 'SUCCESS'
       AND b.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
),
groups AS (
    SELECT normalized_reference,
           transaction_type,
           min(transaction_date)                                                              AS anchor_date,
           array_agg(DISTINCT currency ORDER BY currency)                                     AS currencies,
           count(*) FILTER (WHERE source_type = 'INTERNAL_LEDGER')                            AS ledger_n,
           count(*) FILTER (WHERE source_type = 'GATEWAY')                                    AS gateway_n,
           bool_or(is_duplicate_in_source)                                                    AS flagged_dup,
           -- ORDER BY id: deterministic arrays, so reruns link records in the same order.
           array_agg(id               ORDER BY id) FILTER (WHERE source_type = 'INTERNAL_LEDGER') AS ledger_ids,
           array_agg(transaction_date ORDER BY id) FILTER (WHERE source_type = 'INTERNAL_LEDGER') AS ledger_dates,
           array_agg(id               ORDER BY id) FILTER (WHERE source_type = 'GATEWAY')         AS gateway_ids,
           array_agg(transaction_date ORDER BY id) FILTER (WHERE source_type = 'GATEWAY')         AS gateway_dates,
           min(amount) FILTER (WHERE source_type = 'INTERNAL_LEDGER')                         AS ledger_amount,
           min(amount) FILTER (WHERE source_type = 'GATEWAY')                                 AS gateway_amount,
           min(amount) = max(amount)                                                          AS same_amount
      FROM candidates
     GROUP BY normalized_reference, transaction_type
)
SELECT normalized_reference, transaction_type, currencies,
       ledger_n, gateway_n, ledger_ids, ledger_dates, gateway_ids, gateway_dates,
       ledger_amount, gateway_amount, same_amount,
       -- The order of the branches IS the precedence of rules O1-O5, a business decision
       -- (reconciliation-rules.md §4). NUMERIC equality is by value: 300.0 = 300.00 (MN2).
       CASE
           WHEN ledger_n > 1 OR gateway_n > 1 OR flagged_dup THEN 'DUPLICATE_IN_SOURCE'   -- O1
           WHEN cardinality(currencies) > 1                  THEN 'CURRENCY_MISMATCH'     -- O2
           WHEN ledger_n = 1 AND gateway_n = 1
            AND ledger_amount = gateway_amount               THEN 'MATCH'                 -- O4
           WHEN ledger_n = 1 AND gateway_n = 1               THEN 'AMOUNT_MISMATCH'       -- O3
           WHEN ledger_n = 1                                 THEN 'MISSING_IN_GATEWAY'    -- O5
           WHEN gateway_n = 1                                THEN 'MISSING_IN_LEDGER'     -- O5
           ELSE                                                   'INVARIANT_VIOLATED'    -- no records: impossible
       END
  FROM groups
 WHERE anchor_date = :day                 -- each group processed exactly once
   AND :currency = ANY (currencies)       -- P5: the group involves the run's currency
