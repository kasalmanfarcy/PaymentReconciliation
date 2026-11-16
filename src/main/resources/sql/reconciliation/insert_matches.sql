-- Write the chunk's matches (rule O4): one row per MATCH group, ids generated
-- in the database (uuid_generate_v7, V5). ON CONFLICT DO NOTHING makes a
-- re-executed chunk harmless: rows an earlier attempt committed are skipped.
-- (They can't exist, because a chunk and its checkpoint commit together, but
-- the constraint is the safety net behind the checkpoint.)
INSERT INTO reconciliation_match
    (id, organization_id, run_id,
     ledger_record_id, ledger_txn_date, gateway_record_id, gateway_txn_date,
     match_type, amount_difference)
SELECT uuid_generate_v7(), :org, :runId,
       ledger_ids[1], ledger_dates[1], gateway_ids[1], gateway_dates[1],
       'EXACT', 0
  FROM recon_group
 WHERE outcome = 'MATCH'
ON CONFLICT DO NOTHING
