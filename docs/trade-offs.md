# Trade-offs

**Status:** v1 (M11.11) · **Date:** 2026-11-16

What ReconFlow chose, what each choice cost, what waited and why, what would change at 100× the
planned load, what was measured, and what building it taught. Every claim links to the decision
record, the code or the measurement behind it; numbers come only from [benchmarks.md](benchmarks.md).

---

## 1. Decisions and their costs

**A modular monolith, not services** ([ADR 0002](adr/0002-modular-monolith.md)). One deployable, one
database, one transaction per business step: a chunk's matches, its exception cases and its
checkpoint commit together. Paid for with discipline instead of network boundaries: nine modules with
declared dependencies (`package-info.java`, verified by `ModularityTest`) and ArchUnit rules. The
cost is that the boundaries are only as strong as those tests, and that `reporting` reads other
modules' *schemas* directly (architecture.md §4), a coupling a split into services would have to
replace with an event-fed read model.

**A PostgreSQL job queue instead of Kafka** ([ADR 0004](adr/0004-postgresql-job-queue.md),
[ADR 0014](adr/0014-no-kafka-in-v1.md)). The job row commits in the same transaction as the batch or
run it describes, so there is no dual write and no outbox. Leases, heartbeats, a sweeper and
backoff had to be built by hand ([JobStore](../src/main/java/com/reconflow/paymentreconflow/shared/jobs/JobStore.java),
[JobQueueHardeningIT](../src/test/java/com/reconflow/paymentreconflow/shared/jobs/JobQueueHardeningIT.java)), and
the queue competes with business queries for the same database. Measured: the claim ranked 21st of
67 statements by database time during the morning-burst test, at 0.13 ms a claim and 0.29 jobs/s at
peak ([benchmarks §6](benchmarks.md#6-kafka-decision-inputs-m912)).

**Set-based matching in SQL, not in Java** ([ADR 0012](adr/0012-set-based-matching-in-sql.md)). The
engine groups and classifies a day's records inside PostgreSQL ([classify_groups.sql](../src/main/resources/sql/reconciliation/classify_groups.sql));
Java orchestrates days and checkpoints. Constant memory and one round trip per day, at the price of
logic split between Java and SQL and a dependence on the query planner: stale statistics once made
one day of a run take 47 s instead of 17 ms (§4).

**Partitioning from the first migration** ([ADR 0011](adr/0011-monthly-partitioning-of-transaction-record.md)).
Monthly partitions of `transaction_record` make archival a partition detach and keep indexes small,
but every primary key and unique constraint must include `transaction_date`, partitions need a
maintenance job ([PartitionMaintenance](../src/main/java/com/reconflow/paymentreconflow/ledger/internal/PartitionMaintenance.java)),
and a partition queried by name bypassed row-level security until V10 revoked direct access
([security.md S-06](security.md)).

**One shared schema with row-level security** ([ADR 0008](adr/0008-tenant-isolation.md)). Cheap to
run and to migrate (one schema, not one per tenant), with three layers of isolation: membership per
request, `organization_id` in every query, and RLS for a role that can't bypass it. The cost showed
up as soon as the application really ran as that role (M10.7): every cross-tenant system task must
visit organizations one at a time with each bound ([TenantDirectory](../src/main/java/com/reconflow/paymentreconflow/shared/context/TenantDirectory.java)),
and two existing lookups that ran outside a transaction silently saw no rows, one of them as a `500`
(plans/phase-M10 §9). A tenant that outgrows the shared tables needs its own database (§3).

**`@Version` with conditional requests** ([ADR 0007](adr/0007-optimistic-locking-on-exceptions.md)).
No locks held across a user's think time; a stale write is `409`, a missing `If-Match` is `428`. The
cost is on clients, which must carry the `ETag` and handle a conflict. Measured: 50 users resolving
the same 10 cases got exactly 10 × `200` and 40 × `409`, no lost update ([benchmarks §3, S6](benchmarks.md#3-load-scenarios-s1s6-docsscalabilitymd-7)).

**Building authentication, then delegating it** ([ADR 0013](adr/0013-build-authentication-in-house.md),
[ADR 0015](adr/0015-delegate-authentication-to-keycloak.md)). M3 built login, refresh-token rotation
with reuse detection and lockout to understand them; M10 moved to Keycloak because owning password
storage, MFA and recovery is a liability, not a feature. Because business modules only ever saw
`CurrentUser` and `TenantContext`, the switch changed no business module and the cross-tenant and
authorization suites passed unedited. The cost: Keycloak is another stateful service to run (1 GB
of the deployment's memory budget, [deploy runbook §2](runbooks/deploy.md)), and the deployed realm
has no password grant, so scripts log in with PKCE ([oidc.sh](../scripts/lib/oidc.sh)).

**No Redis.** Rate-limit buckets are in memory per api instance, so N instances allow N× the rate;
the expensive resource, the queue, is protected exactly by a database cap per organization
([security.md S-41, S-42, R-03](security.md)). A cache waits for a measured hot read
([scalability.md §8](scalability.md#8-when-to-scale-beyond-this-design)); none has appeared (read p95
21 ms under 100 users, [benchmarks §3](benchmarks.md#3-load-scenarios-s1s6-docsscalabilitymd-7)).

**One VM with Compose for the deployment, not a PaaS** ([deploy runbook §1](runbooks/deploy.md#1-why-this-shape)).
Chosen because the exact files can be rehearsed on a laptop and because the edge controls (body cap,
per-IP limits) need a proxy of our own. The cost: OS and Docker patching are ours, and an api restart
during a deploy is a few seconds of `502` unless a second api replica is added (runbook §6).

## 2. Deferred, and why

| Deferred | Why it waited |
|---|---|
| One-to-many matching, fees, refunds and chargebacks, settlement windows, bank statements, manual match ([v2 plan](../plans/phase-v2-later-scope.plan)) | Each changes the rules document first; v1 proves one-to-one matching is correct and auditable before making it clever |
| A web UI | The API, its OpenAPI contract and the Bruno collection are the interface; a UI is v2 and the `reconflow-web` client is already in the realm |
| Kafka (M9.13–M9.15) | Every trigger in ADR 0014 is far from met |
| Parquet archives | Gzipped CSV restores byte-exact with no writer dependency (Parquet needs Hadoop's) |
| Per-tenant encryption keys, data residency | Real requirements for some customers, but they shape storage and key management; no customer asked yet |
| The contract migration dropping the in-house auth tables | Held one release after the Keycloak switch so a rollback stays possible (`infra/db/contract/`) |
| Zero-downtime api deploys | Needs a second api replica; not worth the memory for a demo (runbook §6) |

## 3. At 100× (≈100,000 users, ~150M rows a day)

The capacity plan targets 1,000 users and 1.5M rows a day ([scalability.md §2](scalability.md#2-back-of-envelope-estimates));
its §8 lists the signals that trigger each step. At 100×:

- **Ingestion:** `COPY` instead of batched `INSERT`s (imports run at ~34,000 rows/s today,
  [benchmarks §2.3](benchmarks.md)), more workers, and the queue moved to Kafka with a transactional
  outbox once claims appear among the top statements.
- **Database:** read replicas for reports and lists; PgBouncer in transaction mode; sub-partitioning
  by tenant, then a dedicated database for the largest tenants, and sharding by tenant beyond that.
- **Engine:** batching the exception recorder's per-case statements, which dominate exception-heavy
  runs (500k × 500k with 40% exceptions took 304 s, [benchmarks §2.5](benchmarks.md)).
- **Reporting:** pre-aggregated tables or a columnar store instead of OLTP queries.
- **Audit:** streamed to append-only object storage (object lock) instead of a table that only grows.
- **Unchanged:** the domain model, the rules, the idempotency constraints and the module boundaries.
  Those are the parts that are right independent of scale.

## 4. What was measured

All from [benchmarks.md](benchmarks.md), on one laptop (Colima VM, 4 vCPU), so absolute numbers are
indicative; the ratios and the shapes of the plans are the point.

| Measure | Result |
|---|---|
| Import 50k rows | 1.45 s median (34,312 rows/s); target ≥ 1,700 rows/s |
| Reconcile 50k × 50k, 30 days | **53.3 s → 6.49 s** median after two fixes (§2.3) |
| Import 500k rows under `-Xmx256m` | 14.3 s; heap at most 102 MB after GC (§2.5) |
| 100 users browsing (S1) | read p95 21.4 ms, 0 errors in 2,001 requests |
| Morning burst (S2) | every import done 1.0–5.1 s after upload |
| `SIGKILL` of the worker mid-import (S5) | resumed from its checkpoint; identical to a clean import |

**What surprised:** the slow runs were not slow code but a plan built on stale statistics. Each run
started seconds after 100,000 rows were bulk-loaded, before autovacuum analyzed them, and the planner
chose a plan that took 45 s for one day; the same statement took 17 ms a minute later. The fix was
`ANALYZE` after imports plus a query shape (an aggregating `LATERAL`) that is fast whatever the
estimates. And the 28 s "slow" heartbeat `UPDATE` was waiting behind a lock the day transaction held
for its whole duration (§2.2).

## 5. Lessons learned

- **Review the design before the code.** The authentication plan's two review passes
  ([plans/authentication.plan §12–13](../plans/authentication.plan)) found defects that would have been
  bugs: a token-rotation statement order that violated its own foreign key on every refresh, a
  lockout branch that reopened the timing oracle it existed to close, and a check-then-act on "last
  org admin" that two concurrent demotions could both pass.
- **Run tests as the production role.** Switching the suite to the non-owner, RLS-bound role
  (M10.7) found two real bugs nothing else had: lookups outside a transaction see no tenant rows.
- **Measure before tuning.** The plan said "tune batch sizes first"; `pg_stat_statements` said the
  batch sizes were fine and the planner was the problem (§4).
- **Failure tests need a definition of "correct".** `FailureInjectionIT` crashes the pipeline at
  each named point and compares the recovered result with a snapshot of a clean run. Working out
  what "retry is safe" means for an ambiguous commit (the commit landed, the acknowledgement didn't)
  showed that a retried import chunk would have counted its rows twice; the checkpoint is now a
  compare-and-set (plans/phase-M9 §9).
- **What took longer than expected:** Keycloak's realm details (an audience mapper, the Admin API's
  own audience, required profile fields), each a `401` before it was a test (plans/phase-M10 §9).
- **Next time, first:** the deployment shape and the database roles. Both changed assumptions that
  earlier phases had baked in (migrate-on-startup, the owner role in tests).
