# 0004. Background jobs run from a PostgreSQL job table

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M5 (job queue, moved from M9.2), M9.1, M9.3, M9.12

## Context

ADR 0003 makes imports and runs asynchronous, so v1 needs a durable queue. The numbers (scalability.md §2, §4): hundreds of jobs per hour, peaking at a few dozen in the morning burst. Requirements:

- Accepted work must never be lost, including across restarts and crashes.
- A job must start only if its business row exists, and vice versa (no batch without a job, no job without a batch).
- A crashed worker's job must be picked up by another.
- One organization uploading 30 files must not starve the others.

## Decision

We will use a **`job` table in PostgreSQL** (V1), owned by `shared.jobs`, as the queue:

- **Enqueue in the same transaction** as the business row (batch or run) and its audit event.
- **Claim** with `SELECT … FOR UPDATE SKIP LOCKED`, oldest runnable job first (`run_after <= now()`), so concurrent workers never block on or double-claim the same row.
- **Fairness:** skip organizations that already have **2** running jobs.
- **Leases:** a claimed job gets `locked_by` and `lease_until = now() + 2 min`, extended by a heartbeat every 30 s. A **sweeper** re-queues `RUNNING` jobs whose lease expired.
- **Retries:** transient failures go back to `QUEUED` with exponential backoff (`run_after = now() + 2^attempts × 10 s`, capped); permanent failures (bad data, invariant violations) are not retried. After `max_attempts` (5) the job is `FAILED` and its handler fails the batch or run.
- **Idempotent handlers:** a job may run more than once, so handlers resume from checkpoints, use unique constraints and `ON CONFLICT DO NOTHING`, and fence each chunk (check they still own the work before committing).
- Each module plugs in a `JobHandler` per job type (`IMPORT`, `RECONCILE`, `EXPORT`). The worker loop runs only where `reconflow.jobs.worker.enabled` is true (the `worker` profile).

## Consequences

- No new infrastructure: the database we already back up, monitor and run in HA is the queue.
- No dual-write problem: "save the run" and "enqueue its job" are one commit.
- At-least-once execution plus idempotent handlers gives exactly-once *effects*.
- The queue's throughput is bounded by the database. At hundreds of jobs per hour that's irrelevant; the switch-over signal is in scalability.md §8 (claim query in the top-5 slow queries, or more than 50 jobs per second).
- We own the queue's code: claiming, leases, the sweeper, backoff, metrics. It's a few hundred lines and a well-known pattern, but it has to be tested properly (M9.1, M9.4).
- Polling adds a small constant load (one indexed query per worker per second).

## Alternatives considered

- **Kafka (or another broker).** Solves a throughput and fan-out problem we don't have, adds a cluster to run, and reintroduces the dual-write problem (commit the row, then publish) unless paired with a transactional outbox, which is a job table anyway. Deferred until M9.12 measures a need.
- **`@Async` / in-memory executor.** Work is lost on restart or crash, and it can't be shared across instances. Rejected.
- **Quartz.** A scheduler with a JDBC store; possible, but built around triggers and schedules rather than a work queue, with its own tables and configuration, and no per-tenant fairness. Rejected; still a reasonable choice for purely scheduled jobs.
- **A ready-made Postgres queue library** (e.g. db-scheduler, JobRunr). Viable and less code. Rejected for v1 because building the claim/lease/sweeper loop is a deliberate learning goal and the per-org fairness rule is specific; worth reconsidering for a production team.
