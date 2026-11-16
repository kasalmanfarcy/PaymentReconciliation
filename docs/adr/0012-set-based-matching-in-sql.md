# 0012. Matching runs as set-based SQL, chunked by anchor day, orchestrated by Java

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M6.4–M6.9, M6.12

## Context

A daily run for a large tenant compares 100k × 100k records; a monthly run compares 3 million × 3 million (scalability.md §2.4). Loading both sides into Java collections would need gigabytes of heap per run and stream millions of rows across the network. The database already has the data, the indexes and a join engine built for exactly this.

Runs must also survive crashes: a month-long run that fails on day 20 shouldn't redo days 1–19, and must not duplicate their matches.

## Decision

We will run exact matching **inside PostgreSQL as set-based SQL**, with Java orchestrating:

- For each chunk, one `INSERT … SELECT` builds a temporary table of reference **groups** with their per-side counts, currencies, record IDs and amounts, and classifies each group with a `CASE` whose branch order is the rule order O1–O5 in reconciliation-rules.md. Matches are inserted with one more `INSERT … SELECT … ON CONFLICT DO NOTHING`. Only the non-matches (about 2% of groups) come back to Java, which builds `Discrepancy` commands for the exceptions module.
- **Chunking by anchor day.** The run processes its period one day at a time. Each group is assigned to its **anchor day**: the earliest `transaction_date` among its in-period records. The chunk for day D processes exactly the groups anchored on D, and loads **all** of each group's records across the whole period.
- Each day's matches, exception cases and checkpoint (`reconciliation_run.last_completed_date`) commit in **one transaction**. A resumed or retried run starts after the checkpoint.
- The engine sits behind a `MatchingStrategy` interface (`ExactReferenceStrategy` in v1), so v2 strategies (one-to-many, fee-aware) can be added beside it.

## Consequences

- Memory use in the JVM doesn't grow with the data; the database does the join with its indexes (a dedicated `(organization_id, normalized_reference, transaction_type, transaction_date)` index, added in M6).
- Each group is processed exactly once and in full, so cross-day matches, duplicates spanning several days and gateway-only groups need no special cases, and the invariant (every record in exactly one outcome) holds by construction.
- A crash loses at most one day's work; the checkpoint plus `ON CONFLICT DO NOTHING` make a resumed run produce the same result as a clean one.
- The rules live partly in SQL. They must stay in one place (SQL files loaded at startup, not string literals scattered in Java) and be tested against the samples and the property test, because a SQL `CASE` is easy to edit without noticing the precedence changed.
- The engine reads `ledger`'s `transaction_record` table directly, an accepted, read-only exception to module encapsulation (architecture.md §4).
- Matching only happens within the run period; settlement windows beyond it are v2.

## Alternatives considered

- **In-memory matching in Java** (load both sides into maps). Easy to write and test, but a monthly run for a large tenant doesn't fit comfortably in heap, and the data crosses the network twice. Rejected.
- **One SQL statement for the whole period.** Simplest and correct, but a month for a large tenant is one huge transaction, and a crash loses all of it. Rejected.
- **Chunk by the ledger side's date, search the gateway across the period, sweep gateway-only records at the end.** Breaks on duplicates: two ledger records with one reference on different days are seen by two different chunks, each seeing only one of them, so neither notices the duplicate. It also needs a special final sweep. Rejected in favour of anchor days.
- **A stream-processing or batch framework** (Spring Batch, Spark). Heavy machinery for a join PostgreSQL does in seconds. Rejected.
