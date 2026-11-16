-- =====================================================================
-- ReconFlow — least-privilege application role (M10.7)
--
-- Runs as reconflow_migrator, which owns every object below (the roles
-- themselves come from infra/db/bootstrap-roles.sql, run once per
-- environment by a superuser). The application connects as
-- reconflow_app: not the owner and not a superuser, so
--   * it can't DROP, ALTER or disable a trigger (only an owner can), and
--   * row-level security (V4) finally applies to it. Until now the app
--     connected as a superuser, which bypasses every policy.
-- =====================================================================

-- Data access, nothing else: no TRUNCATE, REFERENCES or TRIGGER.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO reconflow_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO reconflow_app;

-- Tables a later migration creates get the same rights automatically, or
-- the first new table would be "permission denied" in production only.
-- FOR ROLE matters: without it the default applies to objects created by
-- whoever ran this statement, which need not be the migrator.
ALTER DEFAULT PRIVILEGES FOR ROLE reconflow_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO reconflow_app;
ALTER DEFAULT PRIVILEGES FOR ROLE reconflow_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO reconflow_app;

-- Append-only tables: read and insert only. The V1/V6 triggers stay as a
-- second, independent lock (an owner could disable a trigger; the app
-- can do neither).
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event, exception_note, exception_status_history
    FROM reconflow_app;

-- The app may read the migration history (startup validation, M11.5) but
-- never write it.
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON flyway_schema_history FROM reconflow_app;

-- ---------------------------------------------------------------------
-- transaction_record partitions
--
-- The RLS policy lives on the parent. A partition queried by name has no
-- policy of its own, so "SELECT * FROM transaction_record_2026_10" would
-- return every tenant's rows. Privileges are checked only on the table a
-- query names, and inserts route through the parent without a check on
-- the partition, so the app loses nothing by having no rights on the
-- partitions themselves.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN SELECT c.relname
               FROM pg_inherits i
               JOIN pg_class c ON c.oid = i.inhrelid
              WHERE i.inhparent = 'public.transaction_record'::regclass
    LOOP
        EXECUTE format('REVOKE ALL ON public.%I FROM reconflow_app', r.relname);
    END LOOP;
END;
$$;

-- The partition jobs need DDL, which only the owner may run. Each gets one
-- narrow SECURITY DEFINER function (runs with the owner's rights) instead
-- of the app getting ownership:
--   * search_path is pinned, so a caller can't put their own "transaction_record"
--     (or an operator) earlier on the path and have the owner run it;
--   * names are built from a DATE inside the function, never from text a
--     caller passes;
--   * EXECUTE is revoked from PUBLIC and granted to the app alone.

-- Replaces V1's version: same behaviour, plus it runs as the owner and
-- revokes the app's default privileges on the new partition (see above).
CREATE OR REPLACE FUNCTION create_transaction_record_partition(p_month DATE) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_start DATE := date_trunc('month', p_month)::date;
    v_end   DATE := (date_trunc('month', p_month) + INTERVAL '1 month')::date;
    v_name  TEXT := format('transaction_record_%s', to_char(v_start, 'YYYY_MM'));
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS public.%I PARTITION OF public.transaction_record FOR VALUES FROM (%L) TO (%L)',
        v_name, v_start, v_end);
    EXECUTE format('REVOKE ALL ON public.%I FROM reconflow_app', v_name);
END;
$$;

-- Archival (M9.16): detach and drop one month, after its rows are exported
-- and verified. Refuses anything that isn't a monthly partition of
-- transaction_record (the default partition included).
CREATE FUNCTION drop_transaction_record_partition(p_month DATE) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_name TEXT := format('transaction_record_%s', to_char(date_trunc('month', p_month), 'YYYY_MM'));
BEGIN
    IF NOT EXISTS (SELECT FROM pg_inherits i
                     JOIN pg_class c ON c.oid = i.inhrelid
                    WHERE i.inhparent = 'public.transaction_record'::regclass
                      AND c.relname = v_name) THEN
        RAISE EXCEPTION 'no partition % of transaction_record', v_name;
    END IF;
    EXECUTE format('ALTER TABLE public.transaction_record DETACH PARTITION public.%I', v_name);
    EXECUTE format('DROP TABLE public.%I', v_name);
END;
$$;

-- M9.7: refresh one month's planner statistics after a bulk load. As a
-- non-owner, ANALYZE only warns and skips the table: without this the
-- statistics fix would silently stop working under the app role.
CREATE FUNCTION analyze_transaction_record_partition(p_month DATE) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_name TEXT := format('transaction_record_%s', to_char(date_trunc('month', p_month), 'YYYY_MM'));
BEGIN
    IF to_regclass('public.' || v_name) IS NOT NULL THEN
        EXECUTE format('ANALYZE public.%I', v_name);
    END IF;
END;
$$;

-- Partition maintenance's alarm: rows in the default partition, across all
-- tenants. Only a number leaves the function, never a row.
CREATE FUNCTION transaction_record_default_rows() RETURNS bigint
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT count(*) FROM public.transaction_record_default
$$;

-- Archival (M9.16): which organizations have rows in a month, so it can
-- bind each in turn. Only ids leave the function.
CREATE FUNCTION transaction_record_partition_organizations(p_month DATE) RETURNS SETOF uuid
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_name TEXT := format('transaction_record_%s', to_char(date_trunc('month', p_month), 'YYYY_MM'));
BEGIN
    IF to_regclass('public.' || v_name) IS NULL THEN
        RETURN;
    END IF;
    RETURN QUERY EXECUTE format('SELECT DISTINCT organization_id FROM public.%I', v_name);
END;
$$;

REVOKE EXECUTE ON FUNCTION create_transaction_record_partition(date) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION drop_transaction_record_partition(date) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION analyze_transaction_record_partition(date) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION transaction_record_default_rows() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION transaction_record_partition_organizations(date) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION create_transaction_record_partition(date) TO reconflow_app;
GRANT EXECUTE ON FUNCTION drop_transaction_record_partition(date) TO reconflow_app;
GRANT EXECUTE ON FUNCTION analyze_transaction_record_partition(date) TO reconflow_app;
GRANT EXECUTE ON FUNCTION transaction_record_default_rows() TO reconflow_app;
GRANT EXECUTE ON FUNCTION transaction_record_partition_organizations(date) TO reconflow_app;
