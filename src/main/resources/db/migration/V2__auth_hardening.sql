-- =====================================================================
-- ReconFlow — auth hardening for M3.3
--
-- Adds what the authentication plan (plans/authentication.plan §10)
-- needs beyond the initial schema. Nothing here changes existing rows:
-- refresh_token is empty until M3.3 ships, which is why family_expires_at
-- can be added NOT NULL without a default or a backfill.
--
-- Safe to run against a database that has V1 applied.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Refresh-token family cap
-- ---------------------------------------------------------------------
-- A refresh chain is capped at 30 days from the login that started it.
-- Without this column the cap cannot be computed after a rotation,
-- because the presented row is no longer the family root.
ALTER TABLE refresh_token
    ADD COLUMN family_expires_at TIMESTAMPTZ NOT NULL;

-- ---------------------------------------------------------------------
-- 2. Why a token was revoked
-- ---------------------------------------------------------------------
-- replaced_by alone distinguishes only "rotated" from "not rotated",
-- which is not enough for the audit trail M3.3h requires. NULL means
-- the token is still live.
ALTER TABLE refresh_token
    ADD COLUMN revoked_reason TEXT
        CHECK (revoked_reason IN ('ROTATED', 'LOGOUT', 'REUSE_DETECTED',
                                  'PASSWORD_CHANGED', 'ADMIN_REVOKED', 'USER_DISABLED'));

-- ---------------------------------------------------------------------
-- 3. Expiry sweeper support
-- ---------------------------------------------------------------------
-- The sweeper deletes rows where expires_at < now() - interval '30 days'.
-- Partial, because live tokens are the only rows it scans.
CREATE INDEX refresh_token_expiry_idx ON refresh_token (expires_at)
    WHERE revoked_at IS NULL;
