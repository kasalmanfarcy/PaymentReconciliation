# 0002. Build a modular monolith, deployed as api and worker roles

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M1.9, M2.8, M2.9

## Context

ReconFlow has distinct areas (ingestion, ledger, reconciliation, exceptions, reporting, audit, security, organizations) that could be separate services. The load numbers in docs/scalability.md §2 say:

- API traffic is small: about 3 requests per second on average and 35 at a burst, against a design target of 100. One instance handles that many times over.
- Data volume is large (1.5 million rows a day), but that's a database problem (partitioning, set-based SQL), not a service-count problem.
- Heavy work (a 100k-row import takes 10–30 s) must not slow the API during the 09:00 upload burst.

The hard problems are correctness and transactions: an import chunk must commit records, counters and its checkpoint together; a reconciliation chunk must commit matches, exception cases and its checkpoint together. In one database, those are local transactions.

## Decision

We will build **one Spring Boot application** with internal modules as top-level packages (`organization`, `security`, `ingestion`, `ledger`, `reconciliation`, `exceptions`, `reporting`, `audit`, `shared`), with:

- each module's public API in its top-level package and everything else internal;
- an explicit, acyclic dependency graph (docs/architecture.md §4);
- boundaries **enforced by tests** (Spring Modulith `ApplicationModules.verify()`, M2.9), not by convention.

The same jar runs in two **roles**, selected by Spring profile: `api` (HTTP) and `worker` (background jobs). Production runs two of each; local development runs both roles in one process.

## Consequences

- Cross-module operations are plain method calls inside one database transaction: no distributed transactions, sagas or outboxes for the core flows.
- One build, one deployment pipeline, one set of logs and metrics.
- Heavy imports and runs execute on worker processes, so they can't starve API threads, and the two roles scale independently.
- The boundaries are only as good as the tests that enforce them; M2.9 must land before modules grow.
- All modules share one database and one release cadence. A module that one day needs independent scaling or a different data store must be extracted, which is feasible only because its boundary was kept clean.
- One deployable means one blast radius: a bad release affects every module. Mitigated by tests and rolling deploys.

## Alternatives considered

- **Microservices** (one service per module). Turns local transactions into distributed ones (the chunk commit above would need a saga or an outbox), multiplies deployments, configuration and on-call surface, and adds network failure modes, all with no load-based reason (scalability.md §2.1). Rejected. We'd revisit it if a module needed a different scaling profile or team ownership that a module boundary can't provide.
- **A traditional layered monolith** (controllers / services / repositories across the whole app, no module boundaries). Simplest at first, but everything can call everything, and extracting anything later means untangling it. Rejected.
- **Separate api and worker codebases.** Two codebases with shared domain code would drift. One jar with two profiles gets the isolation without the duplication. Rejected.
