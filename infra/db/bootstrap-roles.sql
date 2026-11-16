-- =====================================================================
-- ReconFlow — database roles (M10.7). Run once per environment, as a
-- superuser, against the application's database. NOT a Flyway migration:
-- Flyway runs *as* the migrator, so it can't create it.
--
--   psql -v migrator_pw='...' -v app_pw='...' -d paymentreconflow \
--        -f infra/db/bootstrap-roles.sql
--
-- Idempotent: re-running it re-asserts every attribute and sets the given
-- passwords, which is also how a password is rotated.
--
-- Three roles, three jobs:
--   superuser            humans, emergencies only. Never in an app config.
--   reconflow_migrator   owns the schema; Flyway connects as it. Owner
--                        means it can ALTER, DROP and disable triggers.
--   reconflow_app        the running application: DML only (granted by
--                        V10__app_role_grants.sql). Not the owner, not a
--                        superuser, no BYPASSRLS, so row-level security
--                        applies to every query it runs.
-- =====================================================================
\set ON_ERROR_STOP on

-- Fail with a clear message instead of creating a role with an empty password.
\if :{?migrator_pw}
\else
  \echo 'bootstrap-roles.sql: pass -v migrator_pw=...'
  \quit 1
\endif
\if :{?app_pw}
\else
  \echo 'bootstrap-roles.sql: pass -v app_pw=...'
  \quit 1
\endif

-- CREATE ROLE has no IF NOT EXISTS; \gexec runs the statement only when the
-- SELECT returns a row.
SELECT 'CREATE ROLE reconflow_migrator'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'reconflow_migrator') \gexec
SELECT 'CREATE ROLE reconflow_app'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'reconflow_app') \gexec

-- Attributes are spelled out (not left to defaults) so a re-run repairs a
-- role someone loosened by hand.
ALTER ROLE reconflow_migrator WITH LOGIN PASSWORD :'migrator_pw'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE reconflow_app WITH LOGIN PASSWORD :'app_pw'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
    -- docs/scalability.md §2.5: pools of api + worker nodes, plus headroom.
    CONNECTION LIMIT 60;

-- The app must never be able to become the owner: no membership either way.
REVOKE reconflow_migrator FROM reconflow_app;

-- :DBNAME is psql's own variable: the database this script is connected to.
ALTER DATABASE :"DBNAME" OWNER TO reconflow_migrator;
REVOKE ALL ON DATABASE :"DBNAME" FROM PUBLIC;
-- TEMPORARY: the reconciliation engine builds a temp table per day
-- (sql/reconciliation/create_group_table.sql). PUBLIC had it by default;
-- the REVOKE above took it away.
GRANT CONNECT, TEMPORARY ON DATABASE :"DBNAME" TO reconflow_app;

ALTER SCHEMA public OWNER TO reconflow_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;   -- already the default since PostgreSQL 15
GRANT USAGE ON SCHEMA public TO reconflow_app;

-- A database created before M10.7 has its objects owned by whoever ran the
-- migrations then. Hand them to the migrator, so later migrations can
-- alter them and default privileges apply. Extension members (e.g. the
-- pg_stat_statements view) stay with their extension. No-op on a fresh
-- database.
SELECT format('ALTER TABLE public.%I OWNER TO reconflow_migrator', c.relname)
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public'
   AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
   AND pg_get_userbyid(c.relowner) <> 'reconflow_migrator'
   AND NOT EXISTS (SELECT FROM pg_depend d
                    WHERE d.objid = c.oid AND d.classid = 'pg_class'::regclass AND d.deptype = 'e') \gexec
SELECT format('ALTER SEQUENCE public.%I OWNER TO reconflow_migrator', c.relname)
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'S'
   AND pg_get_userbyid(c.relowner) <> 'reconflow_migrator'
   AND NOT EXISTS (SELECT FROM pg_depend d WHERE d.objid = c.oid AND d.deptype IN ('e', 'a', 'i')) \gexec
SELECT format('ALTER FUNCTION public.%I(%s) OWNER TO reconflow_migrator', p.proname,
              pg_get_function_identity_arguments(p.oid))
  FROM pg_proc p
  JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public'
   AND pg_get_userbyid(p.proowner) <> 'reconflow_migrator'
   AND NOT EXISTS (SELECT FROM pg_depend d
                    WHERE d.objid = p.oid AND d.classid = 'pg_proc'::regclass AND d.deptype = 'e') \gexec
