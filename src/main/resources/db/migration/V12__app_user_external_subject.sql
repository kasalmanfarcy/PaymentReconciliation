-- =====================================================================
-- ReconFlow — link users to the identity provider (M10.1, expand step)
--
-- Keycloak now answers "who are you"; ReconFlow still answers "what may
-- you do, in which organization". A Keycloak user is linked to an
-- app_user row by the token's `sub`, stored here: stable and issued by
-- the IdP. Email is used once, to link a pre-provisioned user on first
-- login, and only when Keycloak says it is verified (email can change,
-- and an unverified one can be registered by anybody).
--
-- Expand only: nothing is removed, so the in-house login of M3.3 would
-- still work if the cutover were rolled back. refresh_token and the
-- password/lockout columns go in the contract step, one release later
-- (infra/db/contract/drop_in_house_auth.sql).
-- No backfill: existing users are linked by verified email on their
-- first Keycloak login, or by an admin password reset (MemberService).
-- =====================================================================
ALTER TABLE app_user ADD COLUMN external_subject TEXT;

CREATE UNIQUE INDEX app_user_external_subject_uk
    ON app_user (external_subject) WHERE external_subject IS NOT NULL;

-- Users created through Keycloak have no password here.
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
