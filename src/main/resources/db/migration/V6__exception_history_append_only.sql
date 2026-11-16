-- =====================================================================
-- ReconFlow — investigation history is append-only (M7.2, M7.4)
--
-- exception_note and exception_status_history are evidence: "I checked
-- with the gateway on Monday" must never become something else, and a
-- reopen must never erase how a case was resolved before. Corrections
-- are new rows. Same pattern as audit_event in V1: row triggers block
-- UPDATE and DELETE, a statement trigger blocks TRUNCATE (row triggers
-- don't fire on TRUNCATE).
--
-- A new function rather than V1's audit_event_block_changes(), whose
-- message hard-codes "audit_event"; TG_TABLE_NAME makes this one
-- reusable for any history table.
--
-- The triggers stop bugs, not attackers: a table owner can disable
-- them. M10.7 revokes UPDATE/DELETE from the application role as well.
--
-- No data change, so row-level security (CONTRIBUTING.md) is not
-- involved.
-- =====================================================================

CREATE FUNCTION block_history_changes() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER exception_note_no_update_delete
    BEFORE UPDATE OR DELETE ON exception_note
    FOR EACH ROW EXECUTE FUNCTION block_history_changes();
CREATE TRIGGER exception_note_no_truncate
    BEFORE TRUNCATE ON exception_note
    FOR EACH STATEMENT EXECUTE FUNCTION block_history_changes();

CREATE TRIGGER exception_status_history_no_update_delete
    BEFORE UPDATE OR DELETE ON exception_status_history
    FOR EACH ROW EXECUTE FUNCTION block_history_changes();
CREATE TRIGGER exception_status_history_no_truncate
    BEFORE TRUNCATE ON exception_status_history
    FOR EACH STATEMENT EXECUTE FUNCTION block_history_changes();
