-- =====================================================================
-- ReconFlow — row-level security on tenant-owned tables (M3.5)
--
-- Defense in depth behind the application's organization_id filters.
-- Every transaction sets app.org_id with set_config(..., true), i.e.
-- SET LOCAL, so the value dies at commit/rollback and can't leak to the
-- next user of a pooled connection.
--
-- Not covered by RLS, on purpose:
--   organization, app_user, membership, refresh_token
--       read before any tenant is known (login, TenantContextFilter)
--   job
--       the claim query is cross-tenant by design; the worker binds
--       the job's organization_id before touching tenant data
--
-- FORCE is required because the application currently connects as the
-- table owner, and owners bypass RLS otherwise. Superusers bypass RLS
-- regardless: local dev and Testcontainers connect as superusers, so
-- these policies only take effect once M10.7 gives the application a
-- non-superuser, non-owner role. The tests switch to such a role.
--
-- Consequence for later migrations: a data backfill on these tables,
-- run as the (non-superuser) owner, now sees no rows unless it sets
-- app.org_id per organization. See CONTRIBUTING.md.
-- =====================================================================

-- NULLIF: after any SET LOCAL in a session, current_setting returns ''
-- (not NULL) in later transactions on that connection, and ''::uuid throws.
-- STABLE: evaluated once per query, so indexes leading with
-- organization_id stay usable.
CREATE FUNCTION app_current_org() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.org_id', true), '')::uuid
$$;

-- One policy per table: rows are visible and writable only for the
-- organization bound to the current transaction. No org bound → no rows.
-- USING filters what can be read, updated or deleted; WITH CHECK stops a
-- row being written (inserted or updated) for another organization.
ALTER TABLE import_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_batch FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON import_batch
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

ALTER TABLE import_row_error ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_row_error FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON import_row_error
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

-- Partitioned: the policy on the parent applies to queries on the parent.
-- Never query a partition (transaction_record_YYYY_MM) directly: a
-- partition has no policy of its own.
ALTER TABLE transaction_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE transaction_record FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transaction_record
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

ALTER TABLE reconciliation_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_run FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reconciliation_run
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

ALTER TABLE reconciliation_match ENABLE ROW LEVEL SECURITY;
ALTER TABLE reconciliation_match FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reconciliation_match
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

ALTER TABLE exception_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE exception_case FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exception_case
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

ALTER TABLE exception_case_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE exception_case_record FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exception_case_record
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

ALTER TABLE exception_note ENABLE ROW LEVEL SECURITY;
ALTER TABLE exception_note FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exception_note
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

ALTER TABLE exception_status_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE exception_status_history FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON exception_status_history
    USING (organization_id = app_current_org())
    WITH CHECK (organization_id = app_current_org());

-- audit_event: organization_id is NULL for platform events (logins,
-- failed logins for unknown emails). Those are written with no org bound,
-- and NULL IS NOT DISTINCT FROM NULL is true, so the insert passes.
-- Tenant reads see only their own org's rows; NULL-org rows are visible
-- to no tenant (platform security review uses a privileged role, M10.7).
-- No UPDATE/DELETE policy: with RLS on and no policy for a command, that
-- command matches no rows, on top of the V1 triggers that forbid both.
ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_event FORCE  ROW LEVEL SECURITY;
CREATE POLICY audit_read ON audit_event FOR SELECT
    USING (organization_id = app_current_org());
CREATE POLICY audit_insert ON audit_event FOR INSERT
    WITH CHECK (organization_id IS NOT DISTINCT FROM app_current_org());
