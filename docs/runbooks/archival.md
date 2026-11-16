# Runbook: archived months (M9.16)

`PartitionArchival` (reconciliation module, worker nodes, 03:30 UTC on the 2nd of each month) keeps 13 months of `transaction_record` hot. For each older month it exports, per organization, the month's records, the matches touching them and the case links to them as gzipped CSV under `<organizationId>/archive/<YYYY-MM>/` in the imports bucket, re-reads each file from storage and checks its row count (and, for records, the amount total) against the database, writes one `archive_manifest` row per file, deletes the matches and case links, and finally detaches and drops the partition. A mismatch or failure rolls that organization back with nothing deleted; the next run continues where it stopped (organizations with a manifest are skipped). A month is skipped while any case linked to it is not `RESOLVED`.

Archived data is **not served by the API** in v1. Cases keep their history and their own amounts; only their links to archived records are gone.

## Find what was archived

```sql
SET app.org_id = '<organization id>';   -- RLS: as a superuser, or bind the organization
SELECT month, table_name, object_key, row_count, amount_sum, content_sha256
  FROM archive_manifest WHERE organization_id = '<organization id>' ORDER BY month, table_name;
```

## Look at a month again (an investigation, an auditor)

1. Download the object (MinIO console or `mc cp local/reconflow-imports/<key> .`) and check it: `gunzip -c file.csv.gz | shasum -a 256` must equal `content_sha256`.
2. Load it into a scratch table, never into `transaction_record`:

```sql
CREATE TABLE restore_tr_2025_01 (LIKE transaction_record INCLUDING DEFAULTS);
\copy restore_tr_2025_01 FROM PROGRAM 'gunzip -c transaction_record.csv.gz' WITH (FORMAT csv, HEADER true)
SELECT count(*), sum(amount) FROM restore_tr_2025_01;   -- equals the manifest's row_count / amount_sum
```

3. Drop the scratch table when done. Putting a month back into the live tables (a re-attached partition) is a deliberate migration, not part of this runbook.

## If the job fails

- `Archive … holds N rows / X, the database M / Y: nothing deleted`: the file read back differs from the database. Nothing was deleted; check storage health and rerun (`PartitionArchival.archive(month, now)`) once storage is healthy.
- `WAITING_FOR_ACTIVE_CASES` in the log: an open case links the month. Normal; it is retried next month.
- The drop failed after every organization was archived (a lock timeout): rerun; organizations are skipped and only the drop runs. A foreign-key error on detach means a dependent row was added after the export (should be impossible for a month that old): find it with the queries in `default-partition.md` and rerun.
