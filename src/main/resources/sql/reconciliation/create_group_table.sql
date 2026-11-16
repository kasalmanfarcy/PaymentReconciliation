-- Scratch table for one day-chunk: one row per group anchored on the day.
-- Lives only for the chunk's transaction (ON COMMIT DROP), so the next
-- transaction on the same pooled connection starts clean. Created with
-- explicit columns and filled by INSERT ... SELECT, because CREATE TABLE
-- ... AS is a utility statement and can't take bind parameters.
CREATE TEMP TABLE recon_group (
    normalized_reference TEXT          NOT NULL,
    transaction_type     TEXT          NOT NULL,
    currencies           TEXT[]        NOT NULL,   -- sorted, distinct: the whole group's currencies
    ledger_n             INT           NOT NULL,
    gateway_n            INT           NOT NULL,
    ledger_ids           UUID[],                   -- ordered by id, with ledger_dates in step
    ledger_dates         DATE[],
    gateway_ids          UUID[],
    gateway_dates        DATE[],
    ledger_amount        NUMERIC(19,4),            -- smallest ledger amount
    gateway_amount       NUMERIC(19,4),            -- smallest gateway amount
    same_amount          BOOLEAN       NOT NULL,   -- every record of the group has one amount
    outcome              TEXT          NOT NULL
) ON COMMIT DROP
