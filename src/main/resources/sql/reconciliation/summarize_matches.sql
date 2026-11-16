-- The run's matches: count and total. The ledger amount, which equals the
-- gateway amount for an EXACT match. Joined on (id, transaction_date), the
-- full key of the partitioned table, so each lookup prunes to one partition.
SELECT count(*)                    AS matched_count,
       coalesce(sum(t.amount), 0)  AS matched_amount
  FROM reconciliation_match m
  JOIN transaction_record t
    ON t.id = m.ledger_record_id
   AND t.transaction_date = m.ledger_txn_date
   AND t.organization_id = m.organization_id
 WHERE m.run_id = :runId
   AND m.organization_id = :org
