-- Excluded records (reconciliation-rules.md §2): records of the run's
-- organization, sources and period, in accepted batches (P1-P3, P6), that
-- fail P4 (not SUCCESS) or P5 (their group has no participating record in
-- the run's currency). Counted, never silently dropped: together with the
-- matches and cases they account for every record (the invariant, §4).
WITH accepted AS (
    SELECT t.normalized_reference, t.transaction_type, t.status, t.currency::text AS currency
      FROM transaction_record t
      JOIN import_batch b ON b.id = t.batch_id AND b.organization_id = t.organization_id
     WHERE t.organization_id = :org
       AND t.transaction_date BETWEEN :periodStart AND :periodEnd
       AND t.source_type IN (:leftSource, :rightSource)
       AND b.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
),
in_scope_groups AS (
    SELECT DISTINCT normalized_reference, transaction_type
      FROM accepted
     WHERE status = 'SUCCESS' AND currency = :currency
)
SELECT count(*)
  FROM accepted a
 WHERE a.status <> 'SUCCESS'
    OR NOT EXISTS (SELECT 1 FROM in_scope_groups g
                    WHERE g.normalized_reference = a.normalized_reference
                      AND g.transaction_type = a.transaction_type)
