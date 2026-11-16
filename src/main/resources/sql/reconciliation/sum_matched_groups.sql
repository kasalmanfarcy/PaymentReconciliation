-- The chunk's matched total (M8.1's per-day row): the ledger amount of every
-- MATCH group, which equals the gateway amount for an EXACT match. A MATCH
-- group has exactly one record per side, so ledger_amount is that record's
-- amount, not a minimum over several. Same figure summarize_matches.sql
-- computes for the whole run from reconciliation_match.
SELECT coalesce(sum(ledger_amount), 0)
  FROM recon_group
 WHERE outcome = 'MATCH'
