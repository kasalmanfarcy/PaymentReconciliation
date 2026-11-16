-- Every group of the chunk that isn't a match goes back to Java (about 2% of groups).
-- Sorted so the discrepancies reach the exceptions module in a stable order.
SELECT normalized_reference, transaction_type, currencies, ledger_n, gateway_n,
       ledger_ids, ledger_dates, gateway_ids, gateway_dates,
       ledger_amount, gateway_amount, same_amount, outcome
  FROM recon_group
 WHERE outcome <> 'MATCH'
 ORDER BY outcome, normalized_reference, transaction_type
