# Scalability and Capacity Plan — 1,000 Users

**Status:** Draft · **Target:** v1 production · **Date:** 2026-10-29

This document sizes ReconFlow for **1,000 users** and works out the architecture that load requires. The method is to estimate the load first, then find the bottleneck, then design only for that bottleneck.

**Key finding:** 1,000 users is a *small* request load but a *large* data load. One application node could serve the API traffic many times over. What needs design work is the transaction volume those users import: over 500 million rows a year. The plan below is therefore mostly about PostgreSQL, background jobs and data lifecycle, not about horizontal web scaling.

---

## 1. Assumptions

Every number in this document comes from these assumptions. If an assumption is wrong, change it here and recompute section 2.

| Assumption | Value | Rationale |
|---|---|---|
| Registered users | 1,000 | Target |
| Organizations (tenants) | 100 | ~10 finance users per business |
| Daily active users | 40% → **400** | Finance teams reconcile daily, but not every user every day |
| Peak concurrent users | 25% of DAU → **100** | Activity clusters in the morning, after settlement files arrive |
| Requests per active user | ~2 / min | Browsing exceptions, filtering, viewing details |
| Tenant size mix | 80 small, 15 medium, 5 large | Real B2B usage is long-tailed |
| Internal transactions/day | small 1k · medium 10k · large 100k | Per organization |
| Sources per transaction | 2 (ledger + gateway) | v1 is two-way reconciliation |
| Exception rate | ~2% of transactions | Typical for clean gateway integrations |
| Upload pattern | 70% of files arrive 08:00–10:00 local | Settlement files are produced overnight |
| Retention | 13 months hot, 7+ years archived | Year-over-year comparison; financial record-keeping rules |

---

## 2. Back-of-envelope estimates

### 2.1 Request load (API)

```
peak concurrent users     = 100
average request rate      = 100 users × 2 req/min ÷ 60      ≈ 3.3 req/s
burst factor ×10          →                                   ≈ 35 req/s
design target             =                                    100 req/s  (≈3× headroom)
```

A single Spring Boot instance on 2 vCPU handles hundreds of simple DB-backed requests per second. **The API tier doesn't need horizontal scaling for throughput.** We run two instances for *availability* only.

### 2.2 Data volume

```
internal txns/day   = 80×1k + 15×10k + 5×100k  = 730,000
rows/day (×2 src)                               ≈ 1.5 M rows/day
rows/year                                       ≈ 550 M rows/year
```

| Item | Size per row | Per day | Per year |
|---|---|---|---|
| `transaction_record` heap (incl. raw JSONB) | ~500 B | ~750 MB | ~270 GB |
| Its indexes (3) | ~200 B | ~300 MB | ~110 GB |
| `reconciliation_match` | ~100 B | ~70 MB | ~25 GB |
| `exception_case` + notes | ~400 B × 15k | ~6 MB | ~2 GB |
| `audit_event` | ~300 B × ~50k | ~15 MB | ~5 GB |
| **PostgreSQL total** | | **~1.1 GB/day** | **~410 GB/year** |
| Original CSV files (object storage) | ~150 B | ~225 MB | ~80 GB |

**Consequence:** this is too much for a single unpartitioned table to keep vacuuming, re-indexing and querying comfortably. `transaction_record` needs partitioning and a retention policy from the start (see §5).

### 2.3 Import throughput

```
worst realistic burst  = 5 large orgs × 100k-row file at 09:00   = 500k rows
target                 = all done within 5 minutes                ≈ 1,700 rows/s
PostgreSQL batched insert (JDBC batch / COPY), single node        ≈ 20,000+ rows/s
```

That's about 10× headroom on a single database. **Imports are not the bottleneck,** provided they use batched inserts and stream the file instead of loading it whole.

A 100k-row file takes roughly 10–30 s to parse, validate and insert. That's too long to hold an HTTP request open, so **imports must be asynchronous from v1** (`202 Accepted` plus polling). This settles one of the open questions in the README.

### 2.4 Reconciliation cost

| Run | Records compared | Approach |
|---|---|---|
| Large org, 1 day | 100k × 100k | SQL hash join: about 1–3 s |
| Large org, 1 month | 3M × 3M | Too big to hold comfortably in JVM memory (~1 GB+ of objects). Run it in SQL, in chunks of one day |
| Small org, 1 day | 1k × 1k | Trivial |

**Consequence:** run exact-reference matching **set-based in PostgreSQL** (`INSERT … SELECT … JOIN`) rather than loading both sides into Java collections. Java orchestrates the run and applies the rules that are awkward to express in SQL. The data never has to fit in heap.

### 2.5 Database connections

```
API      2 instances × Hikari pool 10      = 20
Workers  2 instances × (4 threads + 2)     = 12
Admin / migrations / monitoring            ≈  5
total                                      ≈ 37   (PostgreSQL default max_connections = 100)
```

No PgBouncer needed at this scale.

---

## 3. Target architecture

```
                    ┌──────────────────────────┐
  Browser / client ─▶  Load balancer (TLS)     │
                    └────────────┬─────────────┘
                                 │  round-robin, no sticky sessions
                 ┌───────────────┴───────────────┐
                 ▼                               ▼
        ┌─────────────────┐             ┌─────────────────┐
        │ reconflow (api) │             │ reconflow (api) │   2 × 2 vCPU / 4 GB
        │ stateless, JWT  │             │ stateless, JWT  │   profile: api
        └───────┬─────────┘             └────────┬────────┘
                │  writes job rows,              │
                │  stores uploaded files         │
                ▼                                ▼
   ┌───────────────────────┐        ┌──────────────────────────────┐
   │ Object storage (S3 /  │        │ PostgreSQL 16 (managed)      │
   │ MinIO locally)        │        │  primary  + hot standby (HA) │
   │ original CSV files    │        │  PITR backups, 1 TB SSD      │
   └───────────▲───────────┘        │  4 vCPU / 16 GB              │
               │                    │  job table (SKIP LOCKED)     │
               │                    └──────────────▲───────────────┘
               │       claim jobs · write results  │
        ┌──────┴──────────┐             ┌──────────┴──────┐
        │ reconflow       │             │ reconflow       │   2 × 4 vCPU / 8 GB
        │ (worker)        │             │ (worker)        │   profile: worker
        └─────────────────┘             └─────────────────┘

   Observability: Prometheus + Grafana (metrics), JSON logs → log store,
                  traces via OpenTelemetry (optional)
```

### 3.1 Key decisions

| # | Decision | Why | Rejected alternative |
|---|---|---|---|
| D1 | **Modular monolith**: one jar deployed with two profiles, `api` and `worker` | Heavy imports can't starve API threads, while there's still one codebase with one set of transaction boundaries | Microservices: distributed transactions and ops overhead with no load-based reason |
| D2 | **Stateless API** with JWT and no server sessions | Any instance can serve any request; rolling deploys are free | Sticky sessions break failover |
| D3 | **Files in object storage**, not the local disk | With two or more instances, a file uploaded to node A must be readable by a worker on node B | Local filesystem only works with one node |
| D4 | **DB-backed job queue** using `SELECT … FOR UPDATE SKIP LOCKED` | Hundreds of jobs per hour is trivial for PostgreSQL; enqueueing commits in the same transaction as the business row, so there's no dual-write problem | Kafka: solves a throughput problem we don't have (see §8 for when to switch) |
| D5 | **Set-based SQL matching** | Data never has to fit in heap, and the database does joins best | In-memory `Map` matching runs out of memory on monthly runs for large tenants |
| D6 | **Partition `transaction_record` by month** | Keeps indexes small, makes retention a cheap `DETACH PARTITION`, lets queries prune partitions | One table holding 550M+ rows: vacuum and index bloat, expensive deletes |
| D7 | **Shared schema with `organization_id`**, plus PostgreSQL RLS as defense in depth | 100 tenants is far too few to justify schema-per-tenant; RLS catches a forgotten `WHERE` | Schema or DB per tenant: migration fan-out and connection explosion |
| D8 | **No Redis** | Nothing needs caching: hot reads are indexed lookups, and report totals are precomputed at run completion | Cache invalidation risk on financial data, for no measured gain |
| D9 | **No read replica in v1** | 35 req/s peak doesn't need one; the HA standby already covers failover | Add when the trigger in §8 fires |

---

## 4. Background processing

### 4.1 Job table

```sql
CREATE TABLE job (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organization(id),
    type            TEXT        NOT NULL,          -- IMPORT, RECONCILE, EXPORT
    payload         JSONB       NOT NULL,
    status          TEXT        NOT NULL,          -- QUEUED, RUNNING, DONE, FAILED
    attempts        INT         NOT NULL DEFAULT 0,
    max_attempts    INT         NOT NULL DEFAULT 5,
    run_after       TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_by       TEXT,
    lease_until     TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX job_claim_idx ON job (status, run_after) WHERE status = 'QUEUED';
```

### 4.2 Claiming with per-tenant fairness

Without a limit, one large organization uploading 30 files could occupy every worker thread (the *noisy neighbour* problem). Each organization is therefore capped at **2 concurrent jobs**:

```sql
SELECT j.id FROM job j
WHERE j.status = 'QUEUED' AND j.run_after <= now()
  AND (SELECT count(*) FROM job r
       WHERE r.organization_id = j.organization_id AND r.status = 'RUNNING') < 2
ORDER BY j.created_at
FOR UPDATE SKIP LOCKED
LIMIT 1;
```

### 4.3 Leases and recovery

- A worker sets `lease_until = now() + 2 min` and heartbeats every 30 s.
- A sweeper re-queues `RUNNING` jobs whose lease has expired, so a crashed worker's job is picked up by another.
- Re-running a job is safe because of the idempotency constraints in TASKS.md (M5.5, M6.3, M7.3). The lease and the constraints together provide *at-least-once execution with exactly-once effects*.
- Retries use exponential backoff (`run_after = now() + 2^attempts × 10s`). Validation failures are never retried; only transient errors are.
- On graceful shutdown (`SIGTERM`), a worker stops claiming new jobs, finishes or checkpoints the current one, and releases its lease.

### 4.4 Chunking large jobs

- **Import:** stream the CSV and commit every 5,000 rows, recording `last_committed_row` on the batch. A resumed job skips rows that are already committed.
- **Reconciliation:** process one transaction-date day per chunk, with one transaction per chunk, and record the completed days on the run. A resumed run skips completed days.

---

## 5. Database design for volume

### 5.1 Partitioning

```sql
CREATE TABLE transaction_record (
    id                    UUID        NOT NULL,          -- UUIDv7: time-ordered, index-friendly
    organization_id       UUID        NOT NULL,
    batch_id              UUID        NOT NULL,
    source_type           TEXT        NOT NULL,
    normalized_reference  TEXT        NOT NULL,
    amount                NUMERIC(19,4) NOT NULL,
    currency              CHAR(3)     NOT NULL,
    transaction_date      DATE        NOT NULL,
    source_row_number     INT         NOT NULL,
    raw_values            JSONB       NOT NULL,
    -- …
    PRIMARY KEY (id, transaction_date),
    UNIQUE (batch_id, source_row_number, transaction_date)
) PARTITION BY RANGE (transaction_date);
```

- One partition per month holds about 45M rows (~35 GB including indexes), which is comfortable for vacuum and index maintenance.
- A scheduled job (or `pg_partman`) creates partitions **three months ahead**, plus a `DEFAULT` partition that triggers an alert if anything lands in it.
- PostgreSQL requires every unique constraint on a partitioned table to include the partition key, which is why `transaction_date` appears in both constraints above.
- **Partition from M5 onward.** Converting a populated table later means a full data migration.

### 5.2 Indexes (all lead with `organization_id`)

| Index | Serves |
|---|---|
| `(organization_id, source_type, transaction_date, normalized_reference)` | The matching join and date-range filters |
| `(organization_id, batch_id)` | Import detail and transaction search by batch |
| `exception_case (organization_id, status, created_at)` | The exception list, the busiest UI query |
| `exception_case (organization_id, assigned_to, status)` | "My exceptions" |
| `audit_event (organization_id, entity_type, entity_id, created_at)` | Entity timeline |

### 5.3 Data lifecycle

| Age | Location | Mechanism |
|---|---|---|
| 0–13 months | PostgreSQL hot partitions | Normal queries |
| 13 months – 7 years | Object storage as Parquet, per org and month | Monthly job exports the partition, verifies row counts and checksums, then runs `DETACH PARTITION` and `DROP` |
| Original CSVs | Object storage, with lifecycle rules moving them to cold storage after 90 days | Needed for audit and reprocessing |

This keeps the hot database at roughly **450 GB in steady state** instead of growing without limit.

### 5.4 Tenant isolation (defense in depth)

1. **Application:** every repository method takes `organizationId` (TASKS.md M3.5).
2. **Database:** RLS policy `USING (organization_id = current_setting('app.org_id')::uuid)`, set per transaction with `SET LOCAL`. A bug that skips layer 1 returns zero rows instead of another tenant's data.
3. **Tests:** the cross-tenant test suite (M3.9) runs against both layers.

---

## 6. Performance targets (SLOs)

| Metric | Target | Measured by |
|---|---|---|
| API availability (monthly) | 99.5% | LB health and synthetic checks |
| Read endpoints p95 | < 300 ms | Micrometer `http.server.requests` |
| Write endpoints p95 | < 500 ms | Same |
| Upload → `COMPLETED`, 100k-row file, p95 | < 2 min | `import.duration` timer |
| Reconciliation, 100k × 100k day, p95 | < 1 min | `reconciliation.duration` timer |
| Job queue wait at 09:00 peak, p95 | < 2 min | `now() - created_at` at claim |
| Recovery point objective (RPO) | ≤ 5 min | Managed PostgreSQL PITR |
| Recovery time objective (RTO) | ≤ 1 h | Restore drill, run quarterly |

---

## 7. Load test plan

Use **k6** (API) and a dataset generator script (`scripts/generate-dataset`).

| Scenario | Shape | Pass criteria |
|---|---|---|
| S1 Steady browsing | 100 virtual users, 2 req/min each, 30 min, realistic mix (60% exception list, 25% detail, 10% transaction search, 5% reports) | Read SLOs met, error rate < 0.1% |
| S2 Morning burst | 5 × 100k-row uploads plus 20 × 10k-row uploads within 2 min, while S1 runs | Every import completes within 5 min; S1's p95 stays under its SLO (proves API and worker isolation) |
| S3 Noisy neighbour | One org enqueues 30 large files; 10 small orgs upload one each | No small org waits more than 2 min (proves fairness) |
| S4 Month-end | Monthly run for a large org (3M × 3M) | Completes in < 15 min; worker heap stays under 70% |
| S5 Chaos | Kill a worker in the middle of S2 | Jobs resume on the other worker; final state matches a clean run (row counts, totals, no duplicate matches) |
| S6 Contention | 50 virtual users resolving the same 10 exceptions | Exactly one success per exception, the rest `409`, and no lost updates |

Run against infrastructure the **same size as production**, with a database preloaded with 6 months of synthetic data (~270M rows), because query plans on an empty database are meaningless.

---

## 8. When to scale beyond this design

Add infrastructure when a measured signal crosses a threshold, not before.

| Signal (sustained over 1 week) | Action |
|---|---|
| Primary DB CPU > 60% at peak, mostly from report or list reads | Add a read replica and route report and list reads to it |
| Job queue wait p95 > 5 min at peak | Add worker instances (stateless, scales linearly until the DB becomes the limit) |
| Job claim query shows up in the top-5 slow queries, or > 50 jobs/s | Move to Kafka with the transactional outbox pattern (TASKS.md M9.13) |
| Connections > 70% of `max_connections` | Add PgBouncer in transaction mode |
| One tenant > 30% of total rows | Sub-partition by organization, or give the tenant a dedicated database |
| Hot DB > 1 TB | Shorten the hot window or archive more aggressively |
| A repeated expensive read with a stable result and a measured cost | Only then consider a cache (Redis) |

**Rough ceiling:** this design should hold to about **10,000 users / 15M rows a day** with only the first three actions above. Beyond that, the next step is sharding by tenant.

---

## 9. Changes this plan implies for TASKS.md

| Task | Change |
|---|---|
| M1.12 | Resolve the ADRs: async imports (`202`), DB job queue, shared schema plus RLS, UUIDv7 keys |
| M2.4 | Add **MinIO** to `compose.yaml` |
| M2.8 | Add `api` and `worker` Spring profiles that enable different beans |
| M3.5 | Add RLS policies and `SET LOCAL app.org_id` per transaction |
| M5.3 | Create `transaction_record` **partitioned** from the first migration |
| M5.8 | `FileStorage` implemented on S3-compatible storage, not the local disk |
| M5.10–14 | Stream the file, commit in 5,000-row chunks, record a resume checkpoint |
| M6.4–9 | Set-based SQL matching, chunked by day |
| M9.2 | The job table with leases and per-org fairness becomes a **v1 requirement**, moved into M5 |
| M9.5–6 | Run load scenarios S1–S6 from §7 |
| New | Partition maintenance job, archival job to Parquet, quarterly restore drill |

---

## 10. Open questions

- Are the tenant size tiers in §1 realistic for the target market? The 5 large organizations drive 70% of the volume, and the estimates are most sensitive to that number.
- Do any tenants need data residency (e.g. data kept in-region), which would rule out a single shared database?
- Exact retention period by jurisdiction. 7 years is a placeholder; confirm against the target customers' regulations.
- Should original CSV files be encrypted with per-tenant keys?
