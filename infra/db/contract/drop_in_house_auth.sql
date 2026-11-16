-- =====================================================================
-- ReconFlow — contract step of the Keycloak migration (M10.1)
--
-- NOT a Flyway migration yet, deliberately. Ship it one release after the
-- Keycloak cutover has run cleanly: until then, rolling back to the
-- in-house login of M3.3 is still possible because the passwords, lockout
-- state and refresh tokens are still here (expand/contract).
--
-- To ship: copy to src/main/resources/db/migration/V<next free>__drop_in_house_auth.sql
-- (never reuse a number), and remove the Java fields that still mention
-- these columns, if any.
-- =====================================================================
DROP TABLE refresh_token;

ALTER TABLE app_user DROP COLUMN password_hash,
                     DROP COLUMN failed_login_count,
                     DROP COLUMN locked_until,
                     DROP COLUMN must_change_password,
                     DROP COLUMN last_login_at;
