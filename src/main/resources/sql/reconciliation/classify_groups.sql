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
--
-- Shape (M9.7): each group's records are fetched and aggregated in a
-- LATERAL subquery, one index range of transaction_record_reference_idx
-- (organization_id, normalized_reference, transaction_type,
-- transaction_date) per group. An aggregating LATERAL can't be flattened
-- into a plain join, so this plan doesn't depend on the planner's row
-- estimates. The earlier plain join did: for an organization whose rows
-- autovacuum hadn't analyzed yet, the planner estimated one row and
-- chose an unparameterized inner scan of the whole month, read once per
-- group (17,066 rows x 293 groups for one day; docs/benchmarks.md §2.2).
-- The accepted batches (rule P3) are computed once, not joined per row.
INSERT INTO recon_group (normalized_reference, transaction_type, currencies, ledger_n, gateway_n,
                         ledger_ids, ledger_dates, gateway_ids, gateway_dates,
                         ledger_amount, gateway_amount, same_amount, outcome)
WITH accepted AS MATERIALIZED (
    SELECT b.id FROM import_batch b
     WHERE b.organization_id = :org
       AND b.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
),
day_keys AS (
    -- Groups with at least one participating record on :day (any currency).
    SELECT DISTINCT t.normalized_reference, t.transaction_type
      FROM transaction_record t
     WHERE t.organization_id  = :org
       AND t.transaction_date = :day
       AND t.source_type IN (:leftSource, :rightSource)
       AND t.status           = 'SUCCESS'
       AND t.batch_id IN (SELECT id FROM accepted)
),
groups AS (
    -- Every participating record of each of those groups, both sides, any currency, the whole
    -- period, aggregated per group.
    SELECT k.normalized_reference,
           k.transaction_type,
           g.*
      FROM day_keys k
     CROSS JOIN LATERAL (
        SELECT min(t.transaction_date)                                                           AS anchor_date,
               -- CHAR(3) is bpchar: compare and aggregate it as text
               array_agg(DISTINCT t.currency::text ORDER BY t.currency::text)                    AS currencies,
               count(*) FILTER (WHERE t.source_type = 'INTERNAL_LEDGER')                         AS ledger_n,
               count(*) FILTER (WHERE t.source_type = 'GATEWAY')                                 AS gateway_n,
               bool_or(t.is_duplicate_in_source)                                                 AS flagged_dup,
               -- ORDER BY id: deterministic arrays, so reruns link records in the same order.
               array_agg(t.id               ORDER BY t.id) FILTER (WHERE t.source_type = 'INTERNAL_LEDGER') AS ledger_ids,
               array_agg(t.transaction_date ORDER BY t.id) FILTER (WHERE t.source_type = 'INTERNAL_LEDGER') AS ledger_dates,
               array_agg(t.id               ORDER BY t.id) FILTER (WHERE t.source_type = 'GATEWAY')         AS gateway_ids,
               array_agg(t.transaction_date ORDER BY t.id) FILTER (WHERE t.source_type = 'GATEWAY')         AS gateway_dates,
               min(t.amount) FILTER (WHERE t.source_type = 'INTERNAL_LEDGER')                    AS ledger_amount,
               min(t.amount) FILTER (WHERE t.source_type = 'GATEWAY')                            AS gateway_amount,
               min(t.amount) = max(t.amount)                                                     AS same_amount
          FROM transaction_record t
         WHERE t.organization_id      = :org
           AND t.normalized_reference = k.normalized_reference
           AND t.transaction_type     = k.transaction_type
           AND t.transaction_date BETWEEN :periodStart AND :periodEnd
           AND t.source_type IN (:leftSource, :rightSource)
           AND t.status = 'SUCCESS'
           AND t.batch_id IN (SELECT id FROM accepted)
     ) g
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
