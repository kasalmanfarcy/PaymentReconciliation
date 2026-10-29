-- =====================================================================
-- ReconFlow — refresh_token consistency and sweeper index (M3.3)
--
-- Fixes two problems in V2, found in review (plans/authentication.plan
-- §13). V2 is already applied, so it is corrected here rather than
-- edited: changing an applied migration breaks Flyway's checksum
-- validation and the application refuses to start.
--
-- refresh_token is still empty, so the CHECKs need no backfill.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Revocation fields must agree with each other
-- ---------------------------------------------------------------------
-- Without these, a row can be revoked with no reason, carry a reason
-- while still live, or point at a replacement while claiming LOGOUT.
-- Reuse detection keys off revoked_reason = 'ROTATED' (§4.3), so a
-- wrong reason would silently disable it or raise false alarms.
ALTER TABLE refresh_token
    ADD CONSTRAINT refresh_token_revocation_consistent
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL));

ALTER TABLE refresh_token
    ADD CONSTRAINT refresh_token_replaced_only_when_rotated
        CHECK (replaced_by IS NULL OR revoked_reason = 'ROTATED');

-- ---------------------------------------------------------------------
-- 2. An index the sweeper can actually use
-- ---------------------------------------------------------------------
-- V2's index was partial on revoked_at IS NULL, but the sweeper query
-- has no such predicate, so the planner could never use it. It also
-- covered only live tokens, while most rows are revoked (every refresh
-- revokes one).
--
-- The sweeper now deletes whole families past their cap:
--   DELETE FROM refresh_token
--    WHERE family_expires_at < now() - interval '30 days';
-- Deleting by family never removes a rotated token while its family is
-- still live, which reuse detection depends on.
DROP INDEX refresh_token_expiry_idx;

CREATE INDEX refresh_token_family_expiry_idx ON refresh_token (family_expires_at);
