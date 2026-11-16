# 0011. Partition transaction_record by month on transaction_date

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M5.3, partition-maintenance and archival jobs (M5/M9)

## Context

At the target load, `transaction_record` grows by about 1.5 million rows a day, roughly 550 million rows and 380 GB (with indexes) a year (scalability.md §2.2). Data is hot for 13 months and then archived. Almost every query is bounded by date: the matching engine works one day at a time within a run period, and transaction search requires a date range. Converting a large, populated table to a partitioned one later means a full data migration, so the decision has to be made before data exists.

## Decision

We will make `transaction_record` a **range-partitioned table on `transaction_date`, one partition per calendar month** (V1):

- `create_transaction_record_partition(month)` creates `transaction_record_YYYY_MM`; it's idempotent.
- V1 created partitions from 12 months back to 3 months ahead. A daily **partition-maintenance job** keeps the window 3 months ahead, and must exist no later than M5. The importer also creates a missing month's partition before inserting a chunk that needs it.
- A **default partition** catches anything outside the monthly ranges and must stay empty; an alert fires if it isn't, because a monthly partition can't be created for a range while rows for it sit in the default partition.
- **Retention** is by partition: after 13 months, an archival job exports a partition to Parquet in object storage, verifies counts and checksums, then detaches and drops it.
- Because PostgreSQL requires the partition key in every unique constraint, the primary key is `(id, transaction_date)` and the row-level unique key is `(batch_id, source_row_number, transaction_date)`. Foreign keys to a record carry both columns.

## Consequences

- Each partition stays around 45 million rows: vacuum, index builds and statistics stay manageable.
- Date-bounded queries prune to the months they touch; the per-day engine queries read one partition.
- Retention is a cheap `DETACH PARTITION` instead of a `DELETE` of tens of millions of rows and the bloat that follows.
- The composite primary key spreads to every referencing table (`reconciliation_match`, `exception_case_record` carry the date too).
- The unique key can't stop the same source row being inserted twice with different dates; the import retry path covers that gap (database-design.md §4).
- A partition can't be detached while matches or exception links still reference its rows; archival must handle those first.
- Two new jobs (maintenance, archival) must be built and monitored.

## Alternatives considered

- **One unpartitioned table.** Simplest, but at 550 million rows a year vacuum and index maintenance become slow, and retention needs huge deletes. Rejected.
- **Partition by organization** (list or hash). Uneven sizes (five large tenants hold most rows), and it doesn't help retention, which is by time. Rejected; sub-partitioning a giant tenant is a later option (scalability.md §8).
- **Daily or weekly partitions.** Thousands of partitions slow planning and add catalog overhead; monthly matches the retention unit. Rejected.
- **A global row key in a second, unpartitioned table** (`import_row_key (batch_id, source_row_number) PRIMARY KEY`), inserted alongside each record, would close the "same row, different date" gap that the partitioned unique key leaves open. It costs a second insert and a second index per row on the hottest write path, for a risk that needs a normalizer change *between* two attempts at one batch. Rejected for v1: the checkpoint is the primary mechanism, the unique key a guard, and an explicit retry deletes the batch's rows before reprocessing (M5.5, M5.20). Revisit if a resumed import is ever seen to duplicate rows.
- **`pg_partman` for maintenance.** A good choice operationally; not used in v1 to keep the moving parts visible, and because managed PostgreSQL offerings don't all ship it.
