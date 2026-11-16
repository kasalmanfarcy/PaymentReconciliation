# 0014. No Kafka in v1: the measured load doesn't need it

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M9.12 (decides M9.13–M9.15)

## Context

TASKS.md adds Kafka only "if M9.6 shows a need". docs/scalability.md §8 names the signals that would: the job claim among the top statements, more than 50 jobs per second, a queue wait p95 above 5 minutes at the morning burst after adding workers, or a need the job table can't serve (fan-out to independent consumers, replaying history). ADR 0004 chose a PostgreSQL job table, whose enqueue commits with the business row, so there is no dual-write problem today.

M9.6 measured, on one laptop (docs/benchmarks.md §0 for the machine), against a database of 3.7 million records (§2.4):

| Criterion (scalability.md §8) | Threshold | Measured | Met? |
|---|---|---|---|
| Job claim among the top 5 statements by total time (`pg_stat_statements`) | top 5 | Rank **21 of 67** during S2 (the morning burst with 100 users browsing): 550 claims in 8.5 minutes, **72 ms in total**, 0.13 ms each. Rank lower still during S1. The top statements were `ANALYZE` after imports, the import `INSERT`s and the exception list | No |
| Sustained job rate | > 50 jobs/s | S2's burst: 25 imports in 85 s = **0.29 jobs/s** at the peak; S3: 40 imports in ~15 s = 2.6 jobs/s for a few seconds. The 1,000-user target is hundreds of jobs per hour (scalability.md §2) | No |
| Queue wait p95 at the morning burst | > 5 min after adding workers | S2: longest wait from upload to start **1.3 s**, every import done within **5.1 s**, with **one** worker process (4 threads). S3: small tenants waited at most **3.1 s** behind a 30-file tenant | No |
| Fan-out of the same event to several independent consumers or external systems | needed | No consumer outside the monolith in v1; in-process steps (import → case creation) are ordinary calls in one transaction (architecture.md §4) | No |
| Replay of event history | needed | Audit history is `audit_event` (append-only, queryable); reprocessing is an explicit retry of a batch or run | No |

## Decision

We will **not introduce Kafka in v1**. Background work stays on the PostgreSQL job table (ADR 0004), and M9.13 (transactional outbox), M9.14 (idempotent consumers, dead-letter topic) and M9.15 (Kafka integration tests) are skipped.

The decision is reopened when any criterion above is met in production for a week (scalability.md §8), or when an external consumer needs ReconFlow's events. Then, in this order:

1. **Transactional outbox**, not "commit then publish": an `outbox_event` row written in the business transaction, published by a relay (polling with `FOR UPDATE SKIP LOCKED`, the job table's own pattern, or Debezium reading the WAL), marked published after the broker acknowledges. At-least-once by construction.
2. **Idempotent consumers**: a `processed_event (consumer, event_id)` row inserted in the same transaction as the consumer's effect, so a redelivery does nothing; Kafka offsets committed after the database commit.
3. **Dead-letter topic** per consuming topic (Spring Kafka's `DefaultErrorHandler` with `DeadLetterPublishingRecoverer`), using `TransientFailures` (M9.3) to decide retry versus dead-letter, with a metric on its size and a replay procedure.
4. **Ordering** by message key (the organization or the aggregate id), since Kafka orders only within a partition.
5. **Tests** with Testcontainers' Kafka module: outbox atomicity, duplicate delivery applied once, a poison message on the DLT while the partition moves on, per-key order.

Spring Modulith's event publication registry with its Kafka externalization implements steps 1 and 2 and would be evaluated first.

## Consequences

- No broker to run, secure, monitor or upgrade; no second source of truth for "is this work done?".
- Enqueueing stays exactly-once with the business row (one commit), which is the property an outbox would have to rebuild.
- The queue's throughput is bounded by PostgreSQL. The measured margin is wide (the claim costs 0.13 ms; the burst needs a fraction of a job per second), but it is a single database's margin, and it is watched: `reconflow_jobs_queue_wait_seconds` has an SLO alert (M9.10), and `pg_stat_statements` shows the claim's rank.
- Integrations that want push events (a notification service, a data warehouse) have no topic to subscribe to; until one exists they read the API or the audit timeline.
- The numbers are from one laptop with a one-week-sized database, not production hardware; the thresholds are what matters, and they are far away.

## Alternatives considered

- **Kafka now, "to be ready".** Adds a cluster, the outbox, idempotent consumers and a DLT to solve a throughput problem measured at ~1/170th of the threshold. Rejected; the triggers above say when.
- **A lighter broker (RabbitMQ, SQS).** Same dual-write problem and the same outbox, for the same non-problem. Rejected for the same reason.
- **PostgreSQL `LISTEN/NOTIFY` to cut polling latency.** The poll is one indexed query per worker per second (550 claims in 8.5 minutes, 72 ms in total). Not worth the connection handling; revisit only if pickup latency matters.
