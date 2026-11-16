# Benchmarks

**Status:** Started in M8.5 (report query plans); M9.6 adds throughput, latency and memory on the M9.5 dataset · **Date:** 2026-11-16

Measured numbers only. Each section says what was loaded, how it was measured and what the plan looked like, so a regression can be compared against it.

---

## 1. Report queries (M8.5)

### 1.1 Setup

`ReportQueryPlanIT` (runs in `./mvnw verify`, so a plan regression fails the build):

- **Target organization:** 60,000 `transaction_record` rows (30,000 per source, October 2026), 15,000 `exception_case` rows (~70% resolved, a third of the rest assigned, ages 0–59 days) spread over 30 completed runs of 5 days each, one day apart (heavy overlap, as re-runs produce), each with its `reconciliation_run_day` rows and its cases linked on their anchor day; 40 import batches through October with 250 row errors each; one ledger record linked to each case.
- **Seven neighbour organizations** with the same cases, runs and errors: a tenant's report reads a slice of shared tables, and with one tenant alone the planner rightly prefers sequential scans.
- Whatever earlier tests left in the shared Testcontainers database (for example the 120,000 cases of `ExceptionListQueryPlanIT`), then `ANALYZE` on every table involved.
- Each statement is the **production string** (`STATEMENTS` on each `*Queries` class, `ExceptionAgeingQueries.sql(...)`, `ExceptionCsvExporter.EXPORT`), bound with real parameters through JDBC, run twice with `EXPLAIN (ANALYZE, BUFFERS)`; the second (warm) run is recorded. PostgreSQL 16 in Testcontainers on a laptop (Colima), so absolute times are indicative; the plan shapes are the point.
- Assertion: no `Seq Scan` on `exception_case`, `exception_case_run`, `exception_case_record`, `import_row_error` or `transaction_record` (an empty partition shown as `(never executed)` is runtime pruning, not a scan). Small tables (`reconciliation_run`, `reconciliation_run_day`, `import_batch`, `app_user`) may be scanned.

Cold-cache timings (first run after a restart) and the full M9.5 volume (550M records a year) are M9.6's job.

### 1.2 Results

| Statement | Warm | Plan (scan nodes) |
|---|---|---|
| `reconciliation.run` (run row) | 0.01 ms | Index Scan `reconciliation_run_pkey` |
| `reconciliation.run.reasons` | 0.54 ms | Index Scan `exception_case_run_day_idx` (run_id prefix) → Index Scan `exception_case_pkey` |
| `reconciliation.run.resolvers` | 0.43 ms | as above, + Index Scan `app_user_pkey` |
| `reconciliation.range.totals` (31 days) | 0.11 ms | Bitmap Index Scan `reconciliation_run_day_lookup_idx`; hash join to `reconciliation_run` (seq scan of a small table) |
| `reconciliation.range.days` | 0.09 ms | as above |
| `reconciliation.range.reasons` | 3.8 ms | authoritative days as above → Index Scan `exception_case_run_day_idx` on `(run_id, day)` → Index Scan `exception_case_pkey` (3,108 cases) |
| `reconciliation.range.resolvers` | 3.8 ms | as above, + Index Scan `app_user_pkey` |
| `importQuality.batch` | 0.01 ms | Index Scan `import_batch_pkey` |
| `importQuality.batch.errors` | 0.16 ms | Index Scan on the `UNIQUE (batch_id, row_number, column_name, error_code)` index |
| `importQuality.overTime.errors` (31 days) | 2.8 ms | `import_batch` scan → per batch (LATERAL) Index Scan on the batch-led unique index |
| `importQuality.overTime.errors.bySource` | 1.5 ms | Bitmap Index Scan `import_batch_list_idx` → per batch Index Scan as above |
| `importQuality.overTime.denominators` | 0.05 ms | `import_batch` only (`import_batch_list_idx` with a source filter) |
| `ageing` (all active cases) | 2.6 ms | Bitmap Index Scan `exception_case_list_idx` (4,500 active of 15,000) |
| `ageing.assignee` | 0.9 ms | Index Scan `exception_case_assignee_idx` |
| `ageing.unassigned` | 2.0 ms | Bitmap Index Scan `exception_case_list_idx` |
| `export.count` | 0.12 ms | Index (only) Scan on `exception_case_run` by `run_id` |
| `export.rows` (500 cases) | 2.7 ms | `exception_case_run` by `run_id` → `exception_case_pkey` → per case `exception_case_record_pkey` → `transaction_record_2026_10_pkey` (every other partition `never executed`: pruned at run time on `(id, transaction_date)`) |

### 1.3 What the check changed

The first run of the check failed three statements, and each fix is now in the code:

- **Date-range case breakdown** (`reconciliation.range.reasons` / `.resolvers`): the join of the authoritative `(run, day)` pairs to `exception_case_run` was a hash join over a **sequential scan of the whole link table** (7 ms here, and linear in every tenant's history). The primary key `(run_id, exception_id)` gives the run but not the day. Fix: `V8__report_indexes.sql` adds `exception_case_run_day_idx (run_id, day)`; each pair is now one index range. The planner still underestimates the pairs' fan-out (38 rows estimated, 3,108 actual: `run_id` and `day` are correlated, and the planner assumes independence), but the nested loop is the right plan either way. Extended statistics (`CREATE STATISTICS … (dependencies)`) are the fix if that misestimate ever picks a bad plan.
- **Import quality over time**: joining a month of batches to their errors was a hash join over a **sequential scan of `import_row_error`** once one tenant's errors were an eighth of the table. Fix: a rewrite, not an index. The errors are counted per batch in a `LATERAL` subquery that aggregates (so PostgreSQL can't flatten it into a plain join) and summed per bucket; each batch is one range of the existing batch-led unique index. Summing per-batch distinct row counts is exact because a row belongs to one batch.
- **Export**: `Seq Scan on transaction_record_2025_11 … (never executed)`: runtime partition pruning of empty partitions, not a scan. The check now ignores nodes that never executed.

Not added: M8.3's suggested partial index `exception_case (organization_id, created_at) WHERE status <> 'RESOLVED'`. The ageing report's plans use `exception_case_list_idx` as a bitmap scan at 2–3 ms; `<>` can't seek the index's `status` column, but the organization prefix narrows enough. If one organization's active cases reach hundreds of thousands, that partial index is the fix (it holds only the ~2% of cases still active).
