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

**Note on the first S2/S3 attempts.** The k6 scripts first picked each uploader's file by `__VU`, which counts every scenario's users (and `exec.vu.idInScenario` is undefined in k6 v2): S2 uploaded nothing and S3 uploaded duplicates (409). Fixed to `exec.scenario.iterationInTest`; only the reruns are reported.

**Method.** `scripts/bench_import.sh <dataset> <reps>` creates a fresh organization per repetition (a file can be imported once per organization), uploads the ledger and waits for it, uploads the gateway file and waits, requests a run for the dataset's period and waits, then checks every count and total against `expected.json` (all repetitions below matched exactly). One discarded warm-up repetition (JIT), then three measured; the table gives the median with min and max. "DB time" is the server's own `started_at → completed_at`; "wall" adds the HTTP calls, queue wait and 0.2 s polling. k6 runs in Docker (`grafana/k6:1.3.0`) inside the same VM as the database, so it competes with it for CPU: the latencies are pessimistic.

## 1. Report queries (M8.5)

### 1.1 Setup

`ReportQueryPlanIT` (runs in `./mvnw verify`, so a plan regression fails the build):

- **Target organization:** 60,000 `transaction_record` rows (30,000 per source, October 2026), 15,000 `exception_case` rows (~70% resolved, a third of the rest assigned, ages 0–59 days) spread over 30 completed runs of 5 days each, one day apart (heavy overlap, as re-runs produce), each with its `reconciliation_run_day` rows and its cases linked on their anchor day; 40 import batches through October with 250 row errors each; one ledger record linked to each case.
- **Seven neighbour organizations** with the same cases, runs and errors: a tenant's report reads a slice of shared tables, and with one tenant alone the planner rightly prefers sequential scans.
- Whatever earlier tests left in the shared Testcontainers database (for example the 120,000 cases of `ExceptionListQueryPlanIT`), then `ANALYZE` on every table involved.
- Each statement is the **production string** (`STATEMENTS` on each `*Queries` class, `ExceptionAgeingQueries.sql(...)`, `ExceptionCsvExporter.EXPORT`), bound with real parameters through JDBC, run twice with `EXPLAIN (ANALYZE, BUFFERS)`; the second (warm) run is recorded. PostgreSQL 16 in Testcontainers on a laptop (Colima), so absolute times are indicative; the plan shapes are the point.
- Assertion: no `Seq Scan` on `exception_case`, `exception_case_run`, `exception_case_record`, `import_row_error` or `transaction_record` (an empty partition shown as `(never executed)` is runtime pruning, not a scan). Small tables (`reconciliation_run`, `reconciliation_run_day`, `import_batch`, `app_user`) may be scanned.

At the M9.5 volume (3.7 M records) the engine's classify statement was measured right after a PostgreSQL restart and warm (§2.4); the report statements were not re-measured cold: their plans are checked on every build by `ReportQueryPlanIT`, and S1 exercises the reconciliation report on the large database at a p95 of 21 ms (§3).

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


### 2.3 After tuning (M9.7): one change per finding, re-measured the same way

Two changes, each aimed at one finding of §2.2:

1. **`ANALYZE` after a bulk import.** An import that wrote at least 10,000 rows runs `ANALYZE` on the monthly partitions it touched, after the batch is `COMPLETED` (best effort; `ImportStatisticsIT`). `ANALYZE` samples a fixed number of rows (30,000 at the default statistics target), so it costs about the same on a 45M-row partition as on this one: 112 ms mean here.
2. **The day-chunk's lease fence moved to the end of its transaction.** It still runs inside the transaction, so a worker that lost its lease still can't commit; it no longer holds the job row's lock for the whole chunk, so the heartbeat thread isn't blocked behind it.

Fresh database, same dataset, same sequence (first repetition = warm-up):

| Rep | Ledger import (DB) | Ledger rows/s | Gateway import (DB) | Run 30 days (DB) | Records reconciled/s |
|---|---|---|---|---|---|
| warm-up | 1.64 s | 30,337 | 1.48 s | 6.25 s | 15,941 |
| 1 | 1.45 s | 34,312 | 1.46 s | 6.49 s | 15,351 |
| 2 | 1.56 s | 31,892 | 1.45 s | 6.29 s | 15,839 |
| 3 | 1.43 s | 34,792 | 1.45 s | 6.51 s | 15,304 |

| Measure (median of 3) | Before (`165111c`) | After (M9.7) | Target (scalability.md §6) |
|---|---|---|---|
| Import 50k ledger rows | 1.30 s (38,271 rows/s) | 1.45 s (34,312 rows/s) | ≥ 1,700 rows/s; 100k rows < 2 min |
| Reconcile 50k × 50k, 30 days | 53.3 s (45.4–98.0) | **6.49 s** (6.29–6.51) | 100k × 100k day < 1 min |
| Slowest single classify statement | 47.3 s | 0.1 s | — |
| Fence/heartbeat `UPDATE`, max | 28.3 s | < 0.1 s | — |

The import is ~10% slower (within run-to-run noise on this laptop; nothing on its path changed). The run is 8× faster and, more importantly, steady: the spread between repetitions fell from 116% to 3.5%, which is what confirms the diagnosis (a plan problem, not a capacity one). With fresh statistics the whole 30-day run spends little time in the database: across the four runs, `pg_stat_statements` totals 0.4 s for the excluded-records count and 0.2 s for the match totals, and the classify statements are each well under 100 ms. The rest is the per-case statements of the exception recorder (insert, history, audit, links: about 1,100 cases per run) and round trips; at 15,000 records/s that is far inside the SLO, so it was left alone (tuning without a failing target is guessing).

Not changed, deliberately: JDBC batch sizes (imports already run at 20× the target; the plan's 100/500/1,000/5,000 sweep would tune a non-bottleneck) and indexes (every report and engine plan already uses one; §1 and §2.4).

**N+1 checks (M9.7).** `NPlusOneIT` calls every list and detail endpoint for an organization with 20 references and one with 200 (full pages, `size=200`) and counts the statements PostgreSQL executed (`pg_stat_statements` in the test container, so Hibernate's and `JdbcClient`'s alike). Every endpoint runs the same number for both, and none needs more than 9:

| Endpoint | Statements |
|---|---|
| `GET /imports`, `/imports/{id}`, `/imports/{id}/errors` | 5, 5, 5 |
| `GET /transactions` (a `Slice`, no count) | 4 |
| `GET /reconciliation-runs`, `/{id}`, `/{id}/matches` | 6, 4, 6 |
| `GET /exceptions`, `/exceptions/{id}` (a duplicate case with three records) | 5, 8 |
| `GET /audit-events`, `GET /members` | 4, 5 |
| `GET /reports/reconciliation`, `/import-quality`, `/exception-ageing` | 9, 8, 8 |

Each count includes the transaction's `set_config('app.org_id', …)` for row-level security. No N+1 was found: the read paths were written as projections and joins from M5 on (`spring.jpa.open-in-view=false` since M2.7 keeps it that way). `GET /platform/organizations` (cross-tenant, platform admin) and the streamed CSV export aren't in the check.

### 2.4 Background data, and the second plan fix

**Preload.** `scripts/preload.sh` on `generate_dataset.py --preset preload` (10 organizations with long-tailed sizes, 60,000 down to 6,000 references a month, May–October 2026), through the real API: every month imported and reconciled, every count checked against `expected.json`. With the earlier benchmark and chaos organizations the database then held **3.72 million** `transaction_record` rows, 29,700 cases and 2.9 GB. That is about a week of the 1,000-user target's volume (scalability.md §2.2: 1.5 M rows a day): enough for planner behaviour, far from the 270 M rows of a six-month production database (that needs production-sized hardware; not run).

**What it found.** The first organizations' monthly runs took 1–7 s. Then organizations 6 and 7 (10,000 and 8,571 references a month, under the first fix's 10,000-row `ANALYZE` threshold) took **48–124 s** per monthly run (median 55 s and 67 s). `EXPLAIN (ANALYZE)` of one day: the planner, never having seen the organization's id in its statistics, estimated one row and scanned the organization's whole month once per group (17,066 rows × 293 groups). The fix (M9.7, second commit): the classify query aggregates each group's records in a `LATERAL` subquery, which PostgreSQL can't flatten into a plain join, so each group is one index range whatever the estimates; the accepted batches (rule P3) are computed once; and the `ANALYZE` threshold drops to 1,000 rows (below that a bad plan costs groups × rows of a tiny organization: noise).

| Monthly run (30–31 days) of… | Before | After |
|---|---|---|
| organization 6 (10,000 refs) / 7 (8,571 refs), statistics stale | median 55 s / 67 s (max 124 s) | — |
| organizations 7–10 (8,571 → 6,000 refs), same data shape | — | median 0.9–1.2 s (max 1.4 s) |
| organization 1 (60,000 refs, analyzed) | 6.6 s median | unchanged |


**The classify statement at volume** (M6's deferred `EXPLAIN (ANALYZE, BUFFERS)`, on the final query): organization 1 (60,000 refs a month), 15 October, its October partition holding 4.27 M rows of all tenants: 1,950 groups, each one `Index Scan` on `transaction_record_reference_idx` inside the per-group aggregate (1,950 loops, 4 µs each); the day's keys from a bitmap scan of `transaction_record_match_idx` (3,892 rows); `import_batch` read once. **26.4 ms right after a PostgreSQL restart** (shared buffers empty; the VM's file cache stays warm, so this is not a disk-cold number), **16.7 ms warm**; 14,684 buffers.

### 2.5 Stress and memory: 500k × 500k with 206,000 cases, `-Xmx256m` (S4 scaled, M5/M8 carry-overs)

`generate_dataset.py --rows 500000 --seed 77 --mismatch-rate 0.4` (a deliberately bad month: 40% of payments mismatched), app restarted with `-Xmx256m -Xlog:gc`, on the 3.7 M-record database:

| Step | Result |
|---|---|
| Import 497,763 ledger rows | 14.3 s (34,736 rows/s), counts as expected |
| Import 498,855 gateway rows | 14.0 s, counts as expected |
| Reconcile 500k × 500k, 30 days: 294,000 matches, 200,066 mismatches, 4,909 missing, 1,027 duplicates | **303.6 s** (3,283 records/s); summary identical to `expected.json` |
| Export the run's 206,002 cases (`GET /reports/exceptions/export`, over the 50,000 sync limit, so an `EXPORT` job) | `DONE` 3 s after the request; the downloaded CSV has 206,002 rows + header (46 MB) |
| Heap, whole sequence (479 GC pauses, G1) | **max 102 MB after GC** of 256 MB (40%), max 150 MB before GC; no full GC, no `OutOfMemoryError` |

So the 100k-row import and the 200k-row export (deferred from M5 and M8) run in constant memory under `-Xmx256m`, and S4's "worker heap stays under 70%" holds at this scale. The run's time is dominated by the per-case statements of the exception recorder (200,000 new cases, each an insert, a history row, an audit row and links) rather than by matching: at 2% exceptions (the scalability assumption) the 50k run spends 6.5 s for 99,600 records, here 40% exceptions cost 5 minutes for 996,600. S4 itself (3 M × 3 M) was not run: it would need ~6 M more rows than this laptop's VM holds comfortably; scaled linearly from this run it would take ~30 minutes at a 40% exception rate and well under the 15-minute target at 2%, which is an extrapolation, not a measurement. If month-end runs of exception-heavy tenants matter, batching the recorder's per-case statements is the next change (measured first).

---

## 3. Load scenarios S1–S6 (docs/scalability.md §7)

Against the database of §2.4 (3.7 M records, 30,000 cases). k6 v2.4 **on the host** (Homebrew), next to the app: it competes with the app for CPU, so latencies are pessimistic. The first attempts used the `grafana/k6` image inside the Colima VM; there k6's own scenario clock advanced 8 minutes in 52 wall-clock minutes while its virtual users slept (the run never ended and its durations were meaningless), so those results were discarded. Scale factors are stated per scenario; none ran at production size.

| Scenario | Scale run | Result | Pass criteria | Pass |
|---|---|---|---|---|
| S1 Steady browsing | **Full load** (100 users, 2 req/min each, the 60/25/10/5 mix) for **10 min** instead of 30 (access tokens live 15 min and the script logs in once); organization 1 of the preload (60,000 refs/month, 6 runs) | 2,001 requests, 0 errors; reads p50 14.6 ms, **p95 21.4 ms**, p99 22.3 ms, max 30 ms | read p95 < 300 ms, errors < 0.1% | ✓ |
| S2 Morning burst | 5 × 100k-row and 20 × 10k-row uploads (distinct files, 25 organizations) within 85 s, while S1's 100 users browse for 8 min; one worker process, 4 threads | every import **done in 1.0–5.1 s** (median 2.0 s) from upload; longest wait to start 1.3 s; browsing p95 **21.0 ms**, 0 errors in 1,706 requests | imports done < 5 min; browsing p95 < 300 ms | ✓ |
| S3 Noisy neighbour | 30 × 20k-row files from one organization (5 uploaders), then 10 organizations upload 1k rows each 20 s later | the 30 files drained in 15.2 s, each waiting up to 14.3 s behind the per-organization cap of 2; every small organization's import **started within 3.1 s** (median 2.1 s) | no small organization waits > 2 min | ✓ |
| S4 Month-end | Scaled: 500k × 500k with 40% exceptions instead of 3 M × 3 M (§2.5) | 304 s; heap 40% after GC at `-Xmx256m` | < 15 min at full size; heap < 70% | heap ✓; full size not run |
| S5 Chaos | `SIGKILL` (and separately `SIGTERM`) of the only worker during a 500k import (§4) | resumed from its checkpoint; final batch identical to a clean import | jobs resume; state matches a clean run | ✓ |
| S6 Contention | Full (50 users resolving the same 10 open cases at once) | exactly **10 × 200** and **40 × 409**, 0 other statuses; 10 `RESOLVED` history rows for 10 cases (no lost update, no double resolve); p95 80 ms | one success per case, the rest 409 | ✓ |

---

## 4. Crash and shutdown of the real process (S5, M9.4, M9.11)

`scripts/chaos_kill.sh` uploads the 500k ledger file (497,605 rows) into a fresh organization, waits 5 s so the worker is mid-import, signals the packaged app, restarts it and checks the batch against `expected.json`. (The plan asked for the 50k file; 50k imports in 1.5 s, too fast to hit reliably by hand, so the 500k file was used.)

| Signal | State right after | Recovery | Result |
|---|---|---|---|
| `SIGTERM` (graceful) | job `QUEUED`, **attempts 0**, lease cleared; batch `PROCESSING`, checkpoint 155,000 (a chunk boundary). Log: `Released job … on shutdown`, then Tomcat's graceful shutdown | Next start: claimed at once, `Resuming batch … after row 155000`; done 11 s after the restart | 497,605 / 497,357 / 248 rows as expected, no source row twice: **PASS** |
| `SIGKILL` (crash) | job still `RUNNING` with the dead process's lease; batch `PROCESSING`, checkpoint 175,000 | Startup sweep found nothing yet (the lease was still valid: correct, it must not reclaim by host name); the lease expired and the 30 s sweeper re-queued it 2 min after the kill; resumed after row 175,000 | job `DONE` on attempt 2; counts as expected, no source row twice: **PASS** |

The difference is the point of graceful shutdown: about 2 minutes of stall per deploy avoided. Crash recovery doesn't depend on it.

## 5. Probes (M9.11)

Against the benchmark stack, `local` profile, management port 8081:

| Situation | `/actuator/health/liveness` | `/actuator/health/readiness` |
|---|---|---|
| Everything up | 200 | 200 (`db` UP, `storage` UP) |
| PostgreSQL container stopped | **200** | 503 (`db` DOWN: `CannotGetJdbcConnectionException`) |
| MinIO stopped, PostgreSQL back | **200** | 503 (`storage` DOWN: connection refused) |
| Both back | 200 | 200 |

## 6. Kafka decision inputs (M9.12)

From `pg_stat_statements` reset before each scenario. S1 (10 min): the claim ran 632 times (the idle 1 s poll) for 43 ms in total. S2 (8.5 min, the burst): the claim ran 550 times, 72 ms in total (0.13 ms each), **rank 21 of 67** statements by total time; the top three were `ANALYZE` after imports (25 calls, 15.6 s: 0.6 s each on a 4.3 M-row partition, after the batch is already `COMPLETED`), the import `INSERT` batches (9.4 s) and the exception list (8.5 s). Peak job rate 0.29 jobs/s (S2) and ~2.6 jobs/s for 15 s (S3). Decision and thresholds: [ADR 0014](adr/0014-no-kafka-in-v1.md).

## 7. Observability (M9.10)

With `docker compose --profile observability up -d` against the benchmark stack, during a 50k import: Prometheus' target `host.docker.internal:8081/actuator/prometheus` was `up`, all six alert rules loaded (`inactive`), `sum by (outcome) (reconflow_import_rows_total)` returned 99,579 valid and 50 rejected, the provisioned "ReconFlow" dashboard was listed by Grafana's API and its datasource answered the same query. `/actuator/prometheus` on the public port 8080 answered `401`. No screenshot: this environment had no browser.

