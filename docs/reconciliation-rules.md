# Reconciliation rules (v1)

**Status:** Accepted for v1 · **Tasks:** TASKS.md M1.5–M1.8 · **Date:** 2026-11-16

The rulebook for the importer and the engine. Every rule has an ID so tests can name it (M5.21 has one test per normalization rule; M6.18 and M6.19 check the outcome rules). **If the code and this document disagree, the document wins and the code is the bug**, unless the document is changed first, on purpose, in its own commit.

Rule ID prefixes: **N** normalization, **P** participation, **O** outcomes, **D** duplicates, **K** discrepancy keys, **MN** money.

---

## 1. Normalization

Source files are messy: `" pay-106 "`, `"PAY-106"` and `"Pay-106"` are the same payment. Normalization produces a canonical value for comparison while the raw value is kept untouched. We need both: the normalized value to match on, and the raw value to show an investigator exactly what the file said. That is why `transaction_record` has `raw_reference`, `normalized_reference` and `raw_values`.

Normalization runs only on rows that passed validation ([requirements.md §7](requirements.md#7-csv-file-contracts)). Both sources are normalized by the same code with the same rules; a rule that applied to one source only would make the same payment look different on each side.

| ID | Field | Rule | Example |
|---|---|---|---|
| N1 | reference | Trim leading and trailing whitespace, including non-breaking space U+00A0 and other Unicode spaces (`String.strip()`, not `trim()`) | `" pay-106 "` → `"pay-106"` |
| N2 | reference | Unicode NFKC normalization | full-width `ＰＡＹ－１０６` → `PAY-106` |
| N3 | reference | Upper-case with `Locale.ROOT`, never the JVM's default locale | `pay-106` → `PAY-106` |
| N4 | reference | Internal characters are **not** removed or changed in v1: no stripping of hyphens, spaces or leading zeros | `PAY-101` and `PAY101` stay different references |
| N5 | currency | Trim, upper-case (`Locale.ROOT`); must be a valid ISO-4217 code | `aed` → `AED` |
| N6 | amount | Parse with `new BigDecimal(String)`, never via `double`; reject more than 4 decimal places; store at scale 4 | `120.5` → `120.5000` |
| N7 | timestamp | `transaction_at` is parsed as an `OffsetDateTime` and stored as a UTC instant (`TIMESTAMPTZ`). `transaction_date` = `transaction_at` converted to UTC, then `.toLocalDate()` | `2026-10-01T02:00+04:00` → instant `2026-09-30T22:00Z`, date `2026-09-30` |
| N8 | date only | If only `transaction_date` is given (ledger only), use it as is; `transaction_at` is NULL. If both are given, `transaction_at` wins and the file's `transaction_date` is ignored (it stays in `raw_values`) | `2026-10-01` → date `2026-10-01` |
| N9 | status | Map with the table below, case-insensitively after trimming; anything else is row error `UNKNOWN_STATUS` | `captured` → `SUCCESS` |
| N10 | transaction_type | Trim and upper-case; must be `PAYMENT`, `REFUND`, `CHARGEBACK` or `FEE`; blank or missing column → `PAYMENT`; anything else is row error `UNKNOWN_TRANSACTION_TYPE` | `refund` → `REFUND` |
| N11 | raw values | The original row is stored unchanged as JSON in `raw_values`, keyed by the original header text, values as the original strings. `raw_reference` is the reference exactly as in the file, untrimmed | `{"reference": " pay-106 ", "amount": "120.5", …}` |

**Status mapping (N9), both sources:**

| Normalized | Accepted source values (any case) |
|---|---|
| `SUCCESS` | `success`, `succeeded`, `captured`, `settled`, `paid`, `completed` |
| `PENDING` | `pending`, `processing`, `authorized` |
| `FAILED` | `failed`, `declined`, `error` |
| `REVERSED` | `reversed`, `refunded`, `voided`, `cancelled` |

The four normalized values are exactly the `transaction_record.status` CHECK constraint. Source-specific synonyms that aren't in this table (a gateway that writes `sale`) are not added here; they belong to per-organization mappings in v2.

**Why each rule:**

- **N1–N3.** Spaces, full-width characters and case are formatting noise that a human ignores without thinking. NFKC is what turns a full-width `Ｐ` or a non-breaking space into its plain form. `Locale.ROOT` matters because upper-casing `i` on a Turkish-locale JVM gives a dotted `İ`, and the reference would silently stop matching.
- **N4.** Over-normalizing creates **false matches**, which are worse than missed ones: a false match hides a discrepancy, while a missed match at least surfaces as an exception someone looks at. If `PAY101` and `PAY-101` really are the same payment, that's a mapping problem for v2, not something to guess.
- **N7, the timezone rule.** A payment at 02:00 Dubai time (+04:00) on 1 October is 22:00 UTC on **30 September**. If the ledger used its local date and the gateway used UTC, the same payment would land on different dates, and near a period boundary it would be reported missing on both sides. Converting both sources to UTC with the same code puts every record on one clock. The partition a record lands in is chosen by the same date. Sample PAY-202 ([samples/README.md §3](samples/README.md#3-ledger-currency-mismatchcsv--gateway-currency-mismatchcsv)) shows the case.
- **N8.** A ledger that only knows dates can't be converted to UTC, because there's no time to convert. Taking the date as is, is the only honest option; the cost is that a date-only ledger and a timestamped gateway can disagree by a day for payments near midnight UTC. A run whose period covers both days still matches them (§3).
- **N11.** Keeping the original row means a normalization bug can be fixed and the data replayed, instead of the bug being baked into the stored data forever.

---

## 2. Which records take part in a run

A run has an organization, a period `[period_start, period_end]` (both inclusive), a currency, and two sources (`left_source`, `right_source`; by default the ledger and the gateway).

The engine works on **groups**: all records that share `(normalized_reference, transaction_type)`. A record **takes part** in a run when all of these hold:

| ID | Condition | Why |
|---|---|---|
| P1 | `organization_id` equals the run's organization | Tenancy |
| P2 | `source_type` is the run's left or right source | v1 has two sources, so this is every record |
| P3 | Its import batch is `COMPLETED` or `COMPLETED_WITH_ERRORS` | Rows of a batch still `PROCESSING`, or one that `FAILED`, aren't accepted records yet; a retry may delete them |
| P4 | `status = SUCCESS` | Only completed money movements are reconciled |
| P5 | Its **group** contains at least one record (passing P1–P4 and P6) in the run's currency | A run is pinned to one currency ([ADR 0009](adr/0009-run-pinned-to-one-currency.md)). Records in other currencies take part only as the other half of a currency mismatch |
| P6 | `transaction_date` is within `[period_start, period_end]` | The run's period |

A record that fails P4 or P5 but passes P1–P3 and P6 is **excluded**: counted in the run report, never silently dropped. A record outside the period or in another organization simply isn't part of the run at all.

P5 is evaluated on the group, not on each record, so that a ledger record in AED and a gateway record in USD with the same reference end up in the same group. Filtering records by currency first would split them into an AED-only and a USD-only group, and the run would report two misleading "missing" cases instead of one currency mismatch.

A ledger `SUCCESS` against a gateway `FAILED` for the same reference is a real discrepancy (the gateway declined a payment the business thinks succeeded). The `FAILED` record is excluded by P4, so the ledger record becomes `MISSING_IN_GATEWAY` with reason code `COUNTERPART_NOT_SUCCESS` (§5), which tells the investigator what happened.

---

## 3. The match key

Two records are candidates for each other when:

- they have the same `(normalized_reference, transaction_type)`;
- both take part in the run (§2);
- they come from opposite sources.

**The currency is not part of the grouping key**, on purpose: grouping by reference and type, then checking currencies, is what lets the engine see a currency mismatch at all (§4, O2).

**The transaction type is part of the key.** A `PAYMENT` and a `REFUND` with the same reference are separate groups, matched only like-for-like. Linking a refund to its payment is v2 work.

**The transaction date is not part of the key.** A payment dated 1 October in the ledger and 2 October in the gateway (a cross-midnight timestamp, or a late file) still matches if both dates fall inside the run period. If the counterpart is **outside** the period, the record is reported missing. A later run with a wider period will match it, and the earlier case can then be resolved with `TIMING_DIFFERENCE`. Configurable settlement windows that look beyond the period are v2.

Why not include the date: settlement lag and timezone edges routinely put the two halves of one payment on different days. With the date in the key, every such payment would be two false exceptions.

---

## 4. Outcomes, evaluated in order

For each group, the first rule that applies decides the outcome. The order **is** the precedence, and it's a business decision, not an implementation detail.

| ID | Rule | Outcome |
|---|---|---|
| O1 | Either side has more than one record in the group, **or** any record in the group was flagged as a within-file duplicate at import (D1) | One `DUPLICATE_IN_SOURCE` case linking **every** record in the group, both sides. No record in the group is matched or reported missing |
| O2 | The group's records are in more than one currency | One `CURRENCY_MISMATCH` case linking both records |
| O3 | One record on each side, `amount.compareTo(other) != 0` | One `AMOUNT_MISMATCH` case linking both records, with `amount_difference = gateway − ledger`. **Never counted as matched** |
| O4 | One record on each side, equal amounts (by `compareTo`) | A `reconciliation_match` row: `match_type = EXACT`, `amount_difference = 0` |
| O5 | Only one side has a record | `MISSING_IN_GATEWAY` (only the ledger has it) or `MISSING_IN_LEDGER` (only the gateway has it) |

Why this order:

1. **Duplicates first.** If a side has two candidates, nothing else about the group can be trusted: which of the two gateway rows would you compare amounts with?
2. **Currency next.** Amounts in different currencies can't be compared.
3. **Then the one-to-one cases**, decided by amount.
4. **Then the one-sided cases.**

The missing types are named by **source**, not by side: a ledger-only group is `MISSING_IN_GATEWAY` whichever source is the run's left source.

### Duplicates

| ID | Rule |
|---|---|
| D1 | **At import**, every row of a batch that shares `(normalized_reference, transaction_type, amount, currency)` with another row of the same batch gets `is_duplicate_in_source = true`. All copies are flagged; none is dropped; none is a row error. Amounts are compared by value |
| D2 | **In a run**, the engine counts the candidates on each side of a group itself (O1). This catches what D1 can't: the same reference with different amounts in one file, and the same settlement imported twice in two different files (one extra row changes the content hash, so both files are accepted, and every reference then has two records) |

A D1 flag travels with the record: if one copy falls inside a run's period and the other outside it, the in-period copy still makes its group a duplicate. The flag says "this file contained this payment more than once", which is worth a human's attention whichever run sees it.

### Tie-breaking

**There is none.** Whenever a choice would be arbitrary (two candidates for one record), the rules raise an exception for a human instead of choosing. "Take the first one" in a financial system silently hides a possible double charge or a double booking. Surfacing ambiguity, not resolving it, is the engine's job.

### The invariant (NFR-01)

Every record that takes part in a run ends in **exactly one** of: matched, amount mismatch, currency mismatch, missing, or duplicate. Every excluded record is counted as excluded. So, for the records of a run's organization, sources and period:

```
records in = matched × 2
           + amount-mismatch cases × 2
           + currency-mismatch cases × 2
           + missing cases
           + records linked to duplicate cases
           + excluded records
```

The M6.19 property test checks this with random record sets, and [samples/README.md](samples/README.md) checks it by hand for every sample run.

---

## 5. Exception types and reason codes

_Added in M1.6._

---

## 6. Discrepancy identity (`discrepancy_key`)

_Added in M1.6._

---

## 7. State machines

_Added in M1.7._

---

## 8. Money handling

_Added in M1.8._

---

## 9. Worked examples

The full set, with the expected outcome of every record in every sample file, is in [samples/README.md](samples/README.md).

The README's illustrative example (projectoverview.md §1), read with these rules as a one-day run in INR where every record is `SUCCESS` and `PAYMENT`:

| Reference | Ledger | Gateway | Outcome | Rule |
|---|---|---|---|---|
| TXN-101 | 1,000.00 | 1,000.00 | Matched | O4 |
| TXN-102 | 500.00 | — | `MISSING_IN_GATEWAY`, amount 500.00 | O5 |
| TXN-103 | 750.00 | 700.00 | `AMOUNT_MISMATCH`, difference −50.00 | O3 |
| TXN-104 | 250.00 | 250.00 ×2 | `DUPLICATE_IN_SOURCE`, 3 records, reason `SAME_AMOUNT` | O1 |
| TXN-105 | 300.00 | 300.00 | Matched | O4 |

Summary: matched 2 (1,300.00) · amount mismatch 1 (net −50.00) · missing in gateway 1 (500.00) · missing in ledger 0 · duplicate cases 1. Invariant: 10 records (5 ledger, 5 gateway) = 2×2 + 1×2 + 1 + 3 + 0.

The README calls TXN-102 "Unmatched"; in these rules that is `MISSING_IN_GATEWAY`.
