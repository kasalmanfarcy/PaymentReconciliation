# Demo dataset (M11.9)

Two hand-written files, small enough to check by eye, in which every outcome of the engine appears at
least once. [`scripts/demo.sh`](../../../scripts/demo.sh) uploads them, runs the reconciliation and
checks the result against [`expected.json`](expected.json); `DemoDatasetIT` checks the same numbers
on every build, so the demo can't silently drift from the engine.

References are `DEMO-…` and the dates 12–13 October 2026, so they never collide with the other
samples (`PAY-…`, 1–5 October). Like them, the files import until the date window (requirements.md
§7.1) passes October 2028; after that, move the dates forward.

| Reference | Ledger (`ledger.csv`) | Gateway (`gateway.csv`) | Outcome | Rule |
|---|---|---|---|---|
| DEMO-001 … 012 | one record each | the same amount, in UTC | **Matched** (12) | O4; the ledger's `+04:00` times are the same UTC dates (N7) |
| DEMO-013 | `" demo-013 "`, `180.5`, `aed` | `DEMO-013`, `180.50`, `AED` | **Matched** | reference trimmed and upper-cased (N1, N3), amount compared by value (MN2) |
| DEMO-101, 102 | 560.00, 42.00 | — | **`MISSING_IN_GATEWAY`** ×2, 602.00 | O5 |
| DEMO-201, 202 | — | 210.00, 1999.00 (`settled` = success, N9) | **`MISSING_IN_LEDGER`** ×2, 2209.00 | O5 |
| DEMO-301 | 100.00 | 99.97 | **`AMOUNT_MISMATCH`**, −0.03 | O3 (a few fils off) |
| DEMO-302 | 2500.00 | 2000.00 | **`AMOUNT_MISMATCH`**, −500.00 | O3 (the one the demo resolves as `GATEWAY_FEE`) |
| DEMO-401 | 450.00 | 450.00 twice | **`DUPLICATE_IN_SOURCE`** | D1, O1: the engine never picks one |
| DEMO-501 | 400.00 AED | 108.92 USD | **`CURRENCY_MISMATCH`** | O2: the group has an AED record, so it is in this AED run |
| DEMO-601 | — | amount `12.5O` (a letter O) | **row error**, not imported | requirements.md §7 |
| DEMO-602 | — | date `2026-10-32` | **row error**, not imported | requirements.md §7 |

**Imports:** ledger `COMPLETED`, 19 of 19 rows; gateway `COMPLETED_WITH_ERRORS`, 20 of 22 (the two
row errors are listed at `GET /imports/{id}/errors`).

**Run** (AED, 2026-10-12 to 2026-10-13): matched 13 (13,410.14), amount mismatches 2 (net −500.03),
missing in gateway 2 (602.00), missing in ledger 2 (2,209.00), duplicates 1, currency mismatches 1,
excluded 0. Invariant: 19 + 20 = 39 records = 13 × 2 matched + 2 × 2 mismatched + 2 + 2 + 3
(duplicate) + 2 (currency).

**Running it twice** gives the same summary: the uploads answer `409 duplicate-file` (ADR 0005) and the
script carries on with the existing batches; a new run re-observes the still-open cases instead of
creating new ones (K3), and the case resolved by the first demo is resolved for good, so the second
run opens a fresh one for DEMO-302.
