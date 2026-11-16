# Runbook: rows in `transaction_record_default`

**Alert:** `DefaultPartitionNotEmpty` (`reconflow_partitions_default_rows > 0`, M9.10). **Severity:** critical, but not an outage: the rows are stored and queryable.

## Why it matters

`transaction_record` is partitioned by month (ADR 0011). Partition maintenance (`ledger.internal.PartitionMaintenance`, M5.27) keeps partitions created three months ahead, daily at 02:15 UTC and at every worker start. A row dated outside every monthly partition lands in the default partition. PostgreSQL then **refuses to create the monthly partition for that range** ("updated partition constraint for default partition would be violated"), so the longer it sits, the bigger the repair.

The import validator rejects dates more than 31 days ahead or 2 years back (`DATE_OUT_OF_RANGE`, requirements.md §7.1), so normally nothing gets there; rows in the default partition mean maintenance didn't run (no worker for months, a failed job) or the window was configured wider than the partitions.

## Diagnose

```sql
SELECT date_trunc('month', transaction_date)::date AS month, count(*)
  FROM transaction_record_default GROUP BY 1 ORDER BY 1;
```

Check the worker logs for `PartitionMaintenance` errors, and that at least one `worker` instance is running.

Then check whether a run already used them (their foreign keys block the move below):

```sql
SELECT count(*) FROM reconciliation_match m JOIN transaction_record_default d
    ON (d.id, d.transaction_date) IN ((m.ledger_record_id, m.ledger_txn_date), (m.gateway_record_id, m.gateway_txn_date));
SELECT count(*) FROM exception_case_record r JOIN transaction_record_default d
    ON d.id = r.transaction_record_id AND d.transaction_date = r.transaction_date;
```

If either is non-zero, note the affected runs, and in the repair transaction delete those match rows and case links first; afterwards fail and retry (or re-request) the affected runs, which rebuild them idempotently (M6.11). Do it as table owner; until M10.7 every tenant table has `FORCE ROW LEVEL SECURITY`, so run it as a superuser (CONTRIBUTING.md, "Migrations").

## Repair (per month listed above), in one transaction, off-peak

Creating a partition locks the parent table briefly; moving rows locks them. Run it when imports are quiet (not 08:00-10:00 local).

```sql
BEGIN;
-- 1. A standalone table shaped like the parent.
CREATE TABLE transaction_record_2027_03 (LIKE transaction_record INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
-- 2. Move the month's rows out of the default partition (only possible once nothing references
--    them; see above). Ids and dates are kept, so re-built matches and links find them again.
WITH moved AS (
    DELETE FROM transaction_record_default
     WHERE transaction_date >= DATE '2027-03-01' AND transaction_date < DATE '2027-04-01'
    RETURNING *)
INSERT INTO transaction_record_2027_03 SELECT * FROM moved;
-- 3. Attach it: PostgreSQL validates the range and builds the partition's indexes.
ALTER TABLE transaction_record ATTACH PARTITION transaction_record_2027_03
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
COMMIT;
```

Then let maintenance run once (restart a worker, or wait for 02:15 UTC) and check the gauge returns to 0.
