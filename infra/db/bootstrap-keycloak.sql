-- =====================================================================
-- Keycloak's own database and role (M11.4). Run once per environment, as
-- the server's admin user, connected to any database (e.g. postgres):
--
--   psql -v kc_pw='...' -d postgres -f infra/db/bootstrap-keycloak.sql
--
-- A separate database on the same PostgreSQL server: Keycloak manages its
-- own schema (Liquibase) and must not share a schema, a role or a backup
-- schedule decision with ReconFlow's data. reconflow_app and
-- reconflow_migrator can't connect to it, and keycloak can't connect to
-- ReconFlow's database. Idempotent: re-running sets the password.
-- =====================================================================
\set ON_ERROR_STOP on

\if :{?kc_pw}
\else
  \echo 'bootstrap-keycloak.sql: pass -v kc_pw=...'
  \quit 1
\endif

SELECT 'CREATE ROLE keycloak'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'keycloak') \gexec
ALTER ROLE keycloak WITH LOGIN PASSWORD :'kc_pw'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- CREATE DATABASE can't run inside a function or transaction block; \gexec runs it on its own.
SELECT 'CREATE DATABASE keycloak OWNER keycloak'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak') \gexec
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE keycloak TO keycloak;
