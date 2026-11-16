# 0009. A reconciliation run is pinned to one currency

- **Status:** Accepted
- **Date:** 2026-11-16
- **Tasks:** TASKS.md M6.1, M6.5, M8.1

## Context

The README asked whether a run may span currencies. Organizations take payments in several currencies (AED and USD in the samples). A run's summary reports totals: matched amount, net mismatch, unmatched amounts. V1 already has a single `reconciliation_run.currency` column, and assumptions A2 and A3 say each transaction has one currency and there's no FX conversion in v1.

## Decision

We will pin **each run to exactly one currency** (`reconciliation_run.currency`):

- A record takes part in a run only if its reference group contains at least one record in the run's currency (rule P5). Groups entirely in other currencies belong to those currencies' runs and are excluded here.
- When a group mixes currencies (ledger AED, gateway USD for one reference), it takes part in the run of **each** of its currencies and is classified as `CURRENCY_MISMATCH` in all of them. The discrepancy key uses the sorted currency set (`…:AED/USD`), so both runs map to **one** case.
- All summary totals are in the run's currency.
- Multi-currency organizations start one run per currency; the run-request idempotency key includes the currency.

## Consequences

- Every total on a run is a meaningful sum in one currency. Reports never add dirhams to dollars.
- The summary columns stay single-valued (no per-currency child rows).
- Users of multi-currency organizations start several runs. Cheap to automate, but it's more clicks in v1.
- A cross-currency payment (a USD card payment settled in AED) can never match in v1; it always raises `CURRENCY_MISMATCH` for a human. That's correct without FX rates and tolerances, and it's visible rather than hidden.

## Alternatives considered

- **Multi-currency runs.** Totals become meaningless without FX conversion, or the summary needs per-currency rows and every report needs a currency dimension. More complexity for no correctness gain in v1. Rejected.
- **Filter records by currency before grouping.** Simpler SQL, but a mixed-currency payment then appears as `MISSING_IN_GATEWAY` in the AED run and `MISSING_IN_LEDGER` in the USD run: two misleading cases instead of one accurate one. Rejected.
- **Convert to a base currency with FX rates.** Needs a rates source, a rate date convention and a tolerance rule. A v2+ feature with its own ADR.
