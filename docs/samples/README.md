# Sample files and their expected outcomes

**Task:** TASKS.md M1.4 · **Rules:** [reconciliation-rules.md](../reconciliation-rules.md) · **Contract:** [requirements.md §7](../requirements.md#7-csv-file-contracts)

Small, hand-checkable files covering every import and engine scenario. They are the shared truth for the docs, the tests (M5.22 imports every file; M6.18 asserts the run tables) and the demo (M11.9).

These expected outcomes were written from the rules **before** any engine code existed. If the engine and this file disagree, find out which one breaks a rule in reconciliation-rules.md; don't just copy what the code does.

**Machine-checked.** [expected-imports.yaml](expected-imports.yaml) transcribes the import outcomes below (status, counts, every row error, duplicate flags, selected normalized values). `ImportSamplesIT` (M5.22) imports every `.csv` here into a fresh organization and compares; it also fails if a sample has no entry. When you add a sample, add its section here and its entry there.

**Do not reformat these files.** `.gitattributes` marks them `-text` so Git keeps their bytes exactly (the BOM, the CRLF line endings, the spaces around ` pay-106 `), and `.editorconfig` turns off whitespace trimming for them.

---

## How to use them

| Scenario | Upload (source type) | Then run | Expected |
|---|---|---|---|
| Basic | `ledger-basic.csv` (`INTERNAL_LEDGER`), `gateway-basic.csv` (`GATEWAY`) | AED, 2026-10-01 to 2026-10-02 | [§1](#1-ledger-basiccsv--gateway-basiccsv) |
| Encoding | `ledger-bom-crlf.csv` (`INTERNAL_LEDGER`) **instead of** `ledger-basic.csv` | Same as basic | [§2](#2-ledger-bom-crlfcsv) |
| Currency mismatch | `ledger-currency-mismatch.csv`, `gateway-currency-mismatch.csv` | AED, 2026-10-03; then USD, 2026-10-03 | [§3](#3-ledger-currency-mismatchcsv--gateway-currency-mismatchcsv) |
| Duplicates | `gateway-duplicates.csv` (`GATEWAY`) | AED, 2026-10-04 | [§4](#4-gateway-duplicatescsv) |
| Row errors | `ledger-malformed.csv` (`INTERNAL_LEDGER`) | Optional: AED, 2026-10-05 | [§5](#5-ledger-malformedcsv) |
| Upload rejections | `gateway-wrong-headers.csv` (`GATEWAY`), `ledger-header-only.csv` (`INTERNAL_LEDGER`) | — | [§6](#6-upload-rejections) |

The scenarios use different references (PAY-1xx, 2xx, 3xx, 4xx) and different days, so they can all be imported into one organization without affecting each other, **except** `ledger-bom-crlf.csv`: it holds the same rows as `ledger-basic.csv` with different bytes, so importing both into one organization gives every basic reference two ledger records. That's the cross-file duplicate case (rule D1): every basic group becomes `DUPLICATE_IN_SOURCE`. Use a separate organization for the encoding scenario, or upload it instead of `ledger-basic.csv`.

All dates are October 2026. The import date window (requirements.md §7.1) accepts them until October 2028; after that, `DATE_OUT_OF_RANGE` rejects every row and the dates need moving forward.

Amounts below are shown as in the files; the database stores them at scale 4 (`120.5` → `120.5000`, rule N6).

---

## 1. `ledger-basic.csv` + `gateway-basic.csv`

The worked example from projectoverview.md §5, extended so every engine outcome appears at least once.

### Import

| File | Status | Total | Valid | Errors | Notes |
|---|---|---|---|---|---|
| `ledger-basic.csv` | `COMPLETED` | 7 | 7 | 0 | Row 6: `raw_reference` = `" pay-106 "`, `normalized_reference` = `PAY-106` (N1, N3); `aed` → `AED` (N5); `120.5` → `120.5000` (N6). Row 7 is stored with status `FAILED`: a valid row, just not a successful payment |
| `gateway-basic.csv` | `COMPLETED` | 7 | 7 | 0 | `captured`, `settled` → `SUCCESS` (N9). No `transaction_type` column, so every row is `PAYMENT` (N10). Rows 3 and 4 (PAY-104) share reference, type, amount and currency, so **both** get `is_duplicate_in_source = true` (D1) |

Transaction dates (N7: the UTC date of `transaction_at`): ledger 09:15 to 11:30 at +04:00 on 1 October is 05:15 to 07:30 UTC, still 1 October. Every record lands on the same date as its counterpart.

### Run: AED, 2026-10-01 to 2026-10-02

| Reference | Ledger | Gateway | Outcome | Why |
|---|---|---|---|---|
| PAY-101 | 1000.00 | 1000.00 | **Matched** | Same reference, type, currency and amount (rule O4) |
| PAY-102 | 500.00 | — | **`MISSING_IN_GATEWAY`**, amount 500.00 | Only in the ledger (O5) |
| PAY-103 | 750.00 | 700.00 | **`AMOUNT_MISMATCH`**, `amount_difference` = −50.00, amount 750.00 | One record each side, amounts differ (O3). Difference is gateway − ledger |
| PAY-104 | 250.00 | 250.00 ×2 | **`DUPLICATE_IN_SOURCE`**, reason `SAME_AMOUNT`, all 3 records linked, amount 250.00 | Two gateway candidates: the engine never picks one (O1) |
| PAY-105 | 300.00 | 300.0 | **Matched** | `300.00` and `300.0` are equal by `compareTo` (MN2) |
| PAY-106 | ` pay-106 `, 120.5 | 120.50 | **Matched** | Reference trimmed and upper-cased (N1, N3); amount compared by value |
| PAY-107 | 80.00, `FAILED` | — | **Excluded** | Only `SUCCESS` records take part (P4). Counted as excluded, never dropped silently |
| PAY-108 | — | 200.00 | **`MISSING_IN_LEDGER`**, amount 200.00 | Only in the gateway (O5) |

Discrepancy keys: `MISSING_IN_GATEWAY:PAY-102:PAYMENT:AED`, `AMOUNT_MISMATCH:PAY-103:PAYMENT:AED`, `DUPLICATE_IN_SOURCE:PAY-104:PAYMENT:AED`, `MISSING_IN_LEDGER:PAY-108:PAYMENT:AED`.

**Run summary:** matched 3 (1,420.50 AED) · amount mismatch 1 (net −50.00) · missing in gateway 1 (500.00) · missing in ledger 1 (200.00) · duplicate cases 1 · currency mismatches 0 · excluded records 1.

With the default sources (left = ledger, right = gateway), "missing in gateway" is `unmatched_left_*` and "missing in ledger" is `unmatched_right_*`.

**Invariant check (NFR-01):** 14 records in = matched 3×2 (6) + amount mismatch 1×2 (2) + missing 2 + duplicate-group records 3 + excluded 1 = 14.

**Re-running** the same request returns the same run (ADR 0006). A second run with a fresh `Idempotency-Key` creates new matches but no new exception cases: the four active cases are re-observed (rule K3).

---

## 2. `ledger-bom-crlf.csv`

The same seven ledger rows as `ledger-basic.csv`, written the way a Windows spreadsheet export might write them:

- a UTF-8 byte-order mark (`EF BB BF`) before the header;
- CRLF line endings;
- header names in mixed case (`Transaction_At`, `AMOUNT`, `Status`, `TRANSACTION_TYPE`), one padded with spaces (`" Reference "`), and the columns in a different order.

### Import

| Status | Total | Valid | Errors |
|---|---|---|---|
| `COMPLETED` | 7 | 7 | 0 |

Every normalized field (reference, amount, currency, status, type, `transaction_at`, `transaction_date`) is identical to `ledger-basic.csv`'s, row for row. Only two things differ: the `content_hash`, and the keys inside `raw_values`, which keep the original header text (N11), e.g. `"Transaction_At"` instead of `"transaction_at"`.

### Run

Uploaded with `gateway-basic.csv` into an organization that doesn't also hold `ledger-basic.csv`: exactly the §1 outcomes and summary.

---

## 3. `ledger-currency-mismatch.csv` + `gateway-currency-mismatch.csv`

### Import

| File | Status | Total | Valid | Errors | Notes |
|---|---|---|---|---|---|
| `ledger-currency-mismatch.csv` | `COMPLETED` | 2 | 2 | 0 | No `transaction_type` column → `PAYMENT`. PAY-202 is at 02:30 on **4** October in Dubai (+04:00), which is 22:30 UTC on **3** October, so its `transaction_date` is 2026-10-03 (N7) |
| `gateway-currency-mismatch.csv` | `COMPLETED` | 3 | 3 | 0 | `usd`, `aed` → `USD`, `AED` |

PAY-202 is the timezone example: the ledger's local date (4 Oct) and the gateway's UTC date (3 Oct) differ, but rule N7 converts both to UTC, so both records are dated 3 October and match. Without N7 they would be one `MISSING_IN_GATEWAY` and one `MISSING_IN_LEDGER`.

### Run A: AED, 2026-10-03 to 2026-10-03

| Reference | Ledger | Gateway | Outcome | Why |
|---|---|---|---|---|
| PAY-201 | 400.00 AED | 108.92 USD | **`CURRENCY_MISMATCH`**, key `CURRENCY_MISMATCH:PAY-201:PAYMENT:AED/USD`, both records linked, amount 400.00, `amount_difference` NULL | Same reference and type, different currencies; the group has an AED record, so it's in this run's scope (P5, O2) |
| PAY-202 | 150.00 AED | 150.00 AED | **Matched** | Same UTC date thanks to N7 |
| PAY-203 | — | 75.00 USD | **Excluded** (out of this run's currency) | Its group has no AED record, so it belongs to the USD run (P5) |

**Summary:** matched 1 (150.00 AED) · currency mismatches 1 · everything else 0 · excluded records 1.
**Invariant:** 5 in = matched 1×2 + currency mismatch 2 + excluded 1.

### Run B: USD, 2026-10-03 to 2026-10-03 (after run A)

| Reference | Outcome | Why |
|---|---|---|
| PAY-201 | **`CURRENCY_MISMATCH`**, the **same case** as run A | The key uses the sorted currency set, `AED/USD`, so both runs compute the same key. The active case is re-observed and linked to run B; no second case is created (K2, K3) |
| PAY-202 | **Excluded** (out of this run's currency) | Group is AED only |
| PAY-203 | **`MISSING_IN_LEDGER`**, amount 75.00 USD | Only in the gateway, and USD is this run's currency |

**Summary:** matched 0 · currency mismatches 1 · missing in ledger 1 (75.00 USD) · excluded records 2.
**Invariant:** 5 in = currency mismatch 2 + missing 1 + excluded 2.

---

## 4. `gateway-duplicates.csv`

### Import

| Status | Total | Valid | Errors |
|---|---|---|---|
| `COMPLETED` | 6 | 6 | 0 |

| Row | Reference | Amount | `is_duplicate_in_source` | Why |
|---|---|---|---|---|
| 1–3 | PAY-301 | 90.00 | `true` on all three | Same reference, type, amount and currency within the file (D1). None is dropped |
| 4 | PAY-302 | 60.00 | `false` | Same reference as row 5 but a different amount, so not a within-file duplicate by D1's key |
| 5 | PAY-302 | 65.00 | `false` | (as row 4) |
| 6 | PAY-303 | 45.00 | `false` | Unique |

Repeated rows are not row errors: the import succeeds.

### Run: AED, 2026-10-04 to 2026-10-04 (no ledger records on that day)

| Reference | Outcome | Why |
|---|---|---|
| PAY-301 | **`DUPLICATE_IN_SOURCE`**, reason `SAME_AMOUNT`, 3 records linked, amount 90.00 | Three gateway records in one group (O1) |
| PAY-302 | **`DUPLICATE_IN_SOURCE`**, reason `DIFFERENT_AMOUNTS`, 2 records linked, amount 60.00 | Two gateway records in one group. Not flagged at import, but the engine counts candidates itself (D2). `amount` is the smallest gateway amount, because there's no ledger record (§5 of the rules) |
| PAY-303 | **`MISSING_IN_LEDGER`**, amount 45.00 | Only in the gateway |

**Summary:** matched 0 · duplicate cases 2 · missing in ledger 1 (45.00) · everything else 0.
**Invariant:** 6 in = duplicate-group records 3 + 2 + missing 1.

---

## 5. `ledger-malformed.csv`

One valid row, then one row per error code. Header: `reference,amount,currency,status,transaction_date` (a date-only ledger, rule N8).

### Import

| Status | Total | Valid | Errors |
|---|---|---|---|
| `COMPLETED_WITH_ERRORS` | 9 | 1 | 8 |

| Row | Content (abridged) | Result | Error code | Column |
|---|---|---|---|---|
| 1 | `PAY-401,100.00,AED,SUCCESS,2026-10-05` | **Valid** | — | — |
| 2 | `PAY-402,"1,000.00",…` | Error | `INVALID_AMOUNT` | `amount` |
| 3 | `PAY-403,10.12345,…` | Error | `AMOUNT_SCALE_EXCEEDED` | `amount` |
| 4 | `PAY-404,-5.00,…` | Error | `AMOUNT_NOT_POSITIVE` | `amount` |
| 5 | `PAY-405,25.00,XYZ,…` | Error | `INVALID_CURRENCY` | `currency` |
| 6 | `PAY-406,…,01/10/2026` | Error | `INVALID_DATE` | `transaction_date` |
| 7 | `PAY-407,40.00,AED,ON_HOLD,…` | Error | `UNKNOWN_STATUS` | `status` |
| 8 | `,50.00,AED,SUCCESS,…` | Error | `MISSING_REQUIRED_FIELD` | `reference` |
| 9 | `PAY-409,"60.00,AED,SUCCESS,2026-10-05` | Error | `MALFORMED_ROW` | — (whole row) |

Notes:

- Row 2's amount is **quoted** on purpose. Unquoted, `1,000.00` would be split into two fields and the row would be a `MALFORMED_ROW` (wrong field count) instead of an `INVALID_AMOUNT`. That's also how real files break.
- Row 4: `-5.00` is a well-formed decimal, so it fails the positivity check, not the format check (requirements.md §7.5: amount checks stop at the first failure).
- Row 9's quote never closes, so the field runs to the end of the file. The importer reports `MALFORMED_ROW` at row 9 and stops; it's the last record, so nothing else is lost.
- Each bad row has exactly one problem, so each produces exactly one `import_row_error`: 8 error rows in total.

### Run: AED, 2026-10-05 (optional)

| Reference | Outcome |
|---|---|
| PAY-401 | **`MISSING_IN_GATEWAY`**, amount 100.00 (no gateway file for that day) |

The rejected rows were never stored, so they can't take part.

---

## 6. Upload rejections

These are rejected synchronously at `POST /api/v1/imports`. No batch, job, stored file or audit event is created.

| File | Uploaded as | Response | Why |
|---|---|---|---|
| `gateway-wrong-headers.csv` | `GATEWAY` | `400`, code `missing-columns`, `missingColumns: ["merchant_reference", "amount", "currency"]` | Header is `ref,amt,ccy,status,transaction_at`; `status` and `transaction_at` are present, the other three required columns aren't |
| `ledger-header-only.csv` | `INTERNAL_LEDGER` | `400`, code `empty-file` | A header and no data row |

---

## Every row, at a glance

| File | Rows | Imported | Row errors | Rejected at upload |
|---|---|---|---|---|
| `ledger-basic.csv` | 7 | 7 | 0 | — |
| `gateway-basic.csv` | 7 | 7 | 0 | — |
| `ledger-bom-crlf.csv` | 7 | 7 | 0 | — |
| `ledger-currency-mismatch.csv` | 2 | 2 | 0 | — |
| `gateway-currency-mismatch.csv` | 3 | 3 | 0 | — |
| `gateway-duplicates.csv` | 6 | 6 | 0 | — |
| `ledger-malformed.csv` | 9 | 1 | 8 | — |
| `gateway-wrong-headers.csv` | 2 | — | — | `400 missing-columns` |
| `ledger-header-only.csv` | 0 | — | — | `400 empty-file` |
