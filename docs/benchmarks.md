# Benchmarks

**Status:** M8.5 (report query plans), M9.6 (throughput, latency, load scenarios, memory) · **Date:** 2026-11-16

Measured numbers only. Each section says what was loaded, how it was measured and on what, so a regression can be compared against it. Nothing here is an estimate; where something couldn't be measured, it says so.

---

## 0. Environment and method (M9.6)

| | |
|---|---|
| Date / commit | 2026-11-16; baseline at `165111c`, results after tuning at the M9.7 commit and later |
| Machine | Apple M5 laptop, 10 cores, 16 GB RAM, macOS 27.2 |
| Docker | Colima VM (Virtualization.Framework, virtiofs): 4 vCPU, 6 GB, shared by PostgreSQL, MinIO and k6 |
| App | The packaged jar on the host (not in Docker), Java 25 (HotSpot 25.0.5), G1, `-Xmx1g` unless a row says otherwise; profiles `local,api,worker` in one process (4 worker threads), `INFO` logging, no devtools (the repackaged jar excludes it) |
| PostgreSQL | 16.4 (compose image), defaults except `shared_preload_libraries=pg_stat_statements`: `shared_buffers` 128 MB, `work_mem` 4 MB, `max_connections` 100 |
| Isolation | A separate compose project (`docker compose -p reconflow-bench`, its own volumes and an env file outside the repo), so a developer's local data is never touched |
| Dataset | `python3 scripts/generate_dataset.py --preset 50k --out target/datasets/50k` (seed 42: 49,752 ledger and 49,877 gateway rows over October 2026, 1% amount mismatches, 0.5% missing on each side, 0.2% duplicates, 0.1% malformed rows); `--preset 500k` for stress |

**Method.** `scripts/bench_import.sh <dataset> <reps>` creates a fresh organization per repetition (a file can be imported once per organization), uploads the ledger and waits for it, uploads the gateway file and waits, requests a run for the dataset's period and waits, then checks every count and total against `expected.json` (all repetitions below matched exactly). One discarded warm-up repetition (JIT), then three measured; the table gives the median with min and max. "DB time" is the server's own `started_at → completed_at`; "wall" adds the HTTP calls, queue wait and 0.2 s polling. k6 runs in Docker (`grafana/k6:1.3.0`) inside the same VM as the database, so it competes with it for CPU: the latencies are pessimistic.

## 1. Report queries (M8.5)

### 1.1 Setup

`ReportQueryPlanIT` (runs in `./mvnw verify`, so a plan regression fails the build):

- **Target organization:** 60,000 `transaction_record` rows (30,000 per source, October 2026), 15,000 `exception_case` rows (~70% resolved, a third of the rest assigned, ages 0–59 days) spread over 30 completed runs of 5 days each, one day apart (heavy overlap, as re-runs produce), each with its `reconciliation_run_day` rows and its cases linked on their anchor day; 40 import batches through October with 250 row errors each; one ledger record linked to each case.
- **Seven neighbour organizations** with the same cases, runs and errors: a tenant's report reads a slice of shared tables, and with one tenant alone the planner rightly prefers sequential scans.
- Whatever earlier tests left in the shared Testcontainers database (for example the 120,000 cases of `ExceptionListQueryPlanIT`), then `ANALYZE` on every table involved.
- Each statement is the **production string** (`STATEMENTS` on each `*Queries` class, `ExceptionAgeingQueries.sql(...)`, `ExceptionCsvExporter.EXPORT`), bound with real parameters through JDBC, run twice with `EXPLAIN (ANALYZE, BUFFERS)`; the second (warm) run is recorded. PostgreSQL 16 in Testcontainers on a laptop (Colima), so absolute times are indicative; the plan shapes are the point.
- Assertion: no `Seq Scan` on `exception_case`, `exception_case_run`, `exception_case_record`, `import_row_error` or `transaction_record` (an empty partition shown as `(never executed)` is runtime pruning, not a scan). Small tables (`reconciliation_run`, `reconciliation_run_day`, `import_batch`, `app_user`) may be scanned.

Cold-cache timings and plans at the M9.5 volume: §2.4.

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

---

## 2. Import and reconciliation (M9.6)

### 2.1 Baseline: 50k × 50k, before any tuning (commit `165111c`)

Fresh database, one warm-up, three repetitions:

| Rep | Ledger import (DB) | Ledger rows/s | Gateway import (DB) | Run 30 days (DB) | Records reconciled/s |
|---|---|---|---|---|---|
| warm-up | 1.57 s | 31,689 | 1.45 s | 7.21 s | 13,818 |
| 1 | 1.42 s | 35,037 | 1.42 s | 45.37 s | 2,196 |
| 2 | 1.30 s | 38,271 | 1.29 s | 98.03 s | 1,016 |
| 3 | 1.30 s | 38,271 | 1.28 s | 53.28 s | 1,870 |

Imports are steady (~1.3 s for 50k rows, ~38,000 rows/s, 22× the 1,700 rows/s target of scalability.md §2.3). The run is not: 7 s on an empty database, then 45–98 s. The runs vary by far more than 20%, so per the method the cause had to be found before trusting any number: §2.2.

### 2.2 What `pg_stat_statements` showed

Top statements by total time after the four baseline runs:

| Calls | Total | Mean | Max | Statement |
|---|---|---|---|---|
| 229 | 79.4 s | 346 ms | 28.3 s | `UPDATE job SET lease_until = … WHERE id = ? AND locked_by = ? AND status = 'RUNNING'` (the fence and the heartbeat) |
| 1 per run | 44.7–47.3 s | | | `classify_groups.sql` for **one** day of each run (the other 29 days took milliseconds) |
| 4 | 38.6 s | 9.6 s | 38.4 s | `summarize_matches.sql` (the run's totals at completion) |
| 796 | 3.9 s | 4.9 ms | 12 ms | the 500-row `INSERT INTO transaction_record` batches |

The same classify statement, re-run by hand with `EXPLAIN (ANALYZE, BUFFERS)` a minute later, took **17 ms** (index scans on `transaction_record_reference_idx`, 3,215 candidate rows). So the plan, not the query, was the problem: each run started seconds after its organization's 100,000 rows were bulk-loaded, before autovacuum had analyzed them, and the planner, believing the organization had no rows, chose a plan that is catastrophic for 100,000 (one day per run paid for it, until autoanalyze caught up). The 28 s fence/heartbeat `UPDATE` is not slow work but waiting: the day-chunk took the job row's lock with its fence at the start and held it for the whole 45 s transaction, so the heartbeat thread's `UPDATE` of the same row queued behind it. Both are fixed in M9.7 (§2.3).

