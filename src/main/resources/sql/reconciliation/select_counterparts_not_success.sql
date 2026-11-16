-- Reason code COUNTERPART_NOT_SUCCESS (reconciliation-rules.md §5.2): which
-- of the chunk's missing groups have a counterpart in the OTHER source, in
-- the period and in an accepted batch, whose status isn't SUCCESS (so rule
-- P4 excluded it)? E.g. the ledger says paid, the gateway says FAILED: the
-- investigator should see that the gateway declined it, not just "missing".
SELECT DISTINCT g.normalized_reference, g.transaction_type
  FROM recon_group g
  JOIN transaction_record o
    ON o.organization_id      = :org
   AND o.normalized_reference = g.normalized_reference
   AND o.transaction_type     = g.transaction_type
  JOIN import_batch b ON b.id = o.batch_id AND b.organization_id = o.organization_id
 WHERE g.outcome IN ('MISSING_IN_GATEWAY', 'MISSING_IN_LEDGER')
   AND o.source_type = CASE g.outcome WHEN 'MISSING_IN_GATEWAY' THEN 'GATEWAY' ELSE 'INTERNAL_LEDGER' END
   AND o.source_type IN (:leftSource, :rightSource)
   AND o.transaction_date BETWEEN :periodStart AND :periodEnd
   AND o.status <> 'SUCCESS'
   AND b.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
