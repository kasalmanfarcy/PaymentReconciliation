package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.RlsProbe;
import com.reconflow.paymentreconflow.support.TestDatabase;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M10.7: the application's real DataSource (the one every repository, JdbcClient and job uses)
 * connects as a role that can't change the schema, can't rewrite history, and is subject to
 * row-level security. Each statement here runs on that DataSource, inside a transaction that is
 * rolled back, so a test that wrongly succeeds changes nothing.
 *
 * <p>To see these fail, point the app at the owner: give {@link
 * com.reconflow.paymentreconflow.TestcontainersConfiguration#appConnection} the migrator's
 * credentials, and every "permission denied" below turns into success.
 */
class AppRolePrivilegesIT extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void theApplicationIsNeitherSuperuserNorOwnerNorExemptFromRls() {
        Map<String, Object> me = appJdbc.queryForMap("""
                SELECT current_user AS name, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb
                  FROM pg_roles WHERE rolname = current_user""");
        assertThat(me)
                .containsEntry("name", TestDatabase.APP_ROLE)
                .containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false)
                .containsEntry("rolcreaterole", false)
                .containsEntry("rolcreatedb", false);
        // Owns nothing in the schema: ownership is what lets a role ALTER, DROP or skip RLS.
        assertThat(appJdbc.queryForObject(
                        "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE n.nspname = 'public' AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)",
                        Long.class))
                .isZero();
    }

    @Test
    void itCannotChangeTheSchema() {
        assertDenied("DROP TABLE organization");
        assertDenied("ALTER TABLE exception_case ADD COLUMN x int");
        assertDenied("CREATE TABLE sneaky (id int)");
        assertDenied("ALTER TABLE exception_case DISABLE ROW LEVEL SECURITY");
    }

    @Test
    void itCannotRewriteTheAuditTrail() {
        // Disabling the V1 trigger would be the way around it; only the owner may.
        assertDenied("ALTER TABLE audit_event DISABLE TRIGGER ALL");
        // The trigger would stop these too; the REVOKE stops them first, independently.
        assertDenied("UPDATE audit_event SET action = 'LOGOUT'");
        assertDenied("DELETE FROM audit_event");
        assertDenied("TRUNCATE audit_event");
        assertDenied("UPDATE exception_note SET body = 'rewritten'");
        assertDenied("DELETE FROM exception_status_history");
    }

    @Test
    void itCannotWriteTheMigrationHistory() {
        assertThat(appJdbc.queryForObject("SELECT count(*) > 0 FROM flyway_schema_history", Boolean.class))
                .as("reading is allowed (startup validation)")
                .isTrue();
        assertDenied("INSERT INTO flyway_schema_history (installed_rank, description, type, script, installed_by,"
                + " execution_time, success) VALUES (9999, 'x', 'SQL', 'x', 'x', 0, true)");
        assertDenied("DELETE FROM flyway_schema_history");
    }

    @Test
    void aPartitionQueriedByNameIsNotAWayAroundRls() {
        // The policy lives on transaction_record; a partition has none of its own. The app role has
        // no rights on partitions, so it can only reach records through the parent (V10).
        assertDenied("SELECT count(*) FROM transaction_record_default");
        String month = appJdbc.queryForObject(
                "SELECT min(c.relname) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                        + " WHERE i.inhparent = 'transaction_record'::regclass AND c.relname <> 'transaction_record_default'",
                String.class);
        assertDenied("SELECT count(*) FROM " + month);
        // The maintenance alarm still works, through its SECURITY DEFINER function.
        assertThat(appJdbc.queryForObject("SELECT transaction_record_default_rows()", Long.class))
                .isNotNull();
    }

    @Test
    void theDefinerFunctionsCanOnlyBeCalledByTheApp() {
        // EXECUTE was revoked from PUBLIC: a role that isn't the app (or the owner) can't borrow the
        // owner's rights through them.
        Long publicExecutable = jdbc.queryForObject("""
                SELECT count(*) FROM pg_proc p
                 WHERE p.prosecdef
                   AND p.pronamespace = 'public'::regnamespace
                   AND has_function_privilege('public', p.oid, 'EXECUTE')""", Long.class);
        Long withoutPinnedSearchPath = jdbc.queryForObject("""
                SELECT count(*) FROM pg_proc p
                 WHERE p.prosecdef
                   AND p.pronamespace = 'public'::regnamespace
                   AND NOT coalesce(p.proconfig::text[] @> ARRAY['search_path=public, pg_temp'], false)""", Long.class);
        assertThat(publicExecutable).isZero();
        assertThat(withoutPinnedSearchPath)
                .as("every SECURITY DEFINER function pins search_path")
                .isZero();
    }

    @Test
    void rowLevelSecurityAppliesToTheAppEvenWithNoWhereClause() throws Exception {
        UUID orgA = data().newOrganization();
        UUID orgB = data().newOrganization();
        UUID user = data().newUser().id();
        newCase(orgA, user);
        newCase(orgA, user);
        newCase(orgB, user);

        RlsProbe probe = new RlsProbe(dataSource);
        // No WHERE at all: the shared test database holds many tenants' cases, but org A sees two.
        long asA = probe.asTenant(orgA, db -> db.queryForObject("SELECT count(*) FROM exception_case", Long.class));
        long asB = probe.asTenant(orgB, db -> db.queryForObject("SELECT count(*) FROM exception_case", Long.class));
        long asNobody =
                probe.asTenant(null, db -> db.queryForObject("SELECT count(*) FROM exception_case", Long.class));

        assertThat(asA).isEqualTo(2);
        assertThat(asB).isEqualTo(1);
        assertThat(asNobody).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exception_case", Long.class))
                .as("the superuser sees every tenant's cases")
                .isGreaterThanOrEqualTo(3);
    }

    private int runs;

    /** A run and one open case for it, written as the superuser (fixtures bypass RLS). */
    private void newCase(UUID org, UUID user) {
        UUID run = Ids.newId();
        jdbc.update(
                """
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, triggered_by)
                VALUES (?, ?, ?, ?, DATE '2026-01-01' + ?, DATE '2026-01-01' + ?, 'AED', ?)""",
                run,
                org,
                "client:" + run,
                "0".repeat(64),
                // one active run per (org, period): each fixture run gets its own day
                ++runs,
                runs,
                user);
        jdbc.update("""
                INSERT INTO exception_case (id, organization_id, run_id, type, discrepancy_key, currency, amount)
                VALUES (?, ?, ?, 'MISSING_IN_GATEWAY', ?, 'AED', 10)""", Ids.newId(), org, run, "MISSING_IN_GATEWAY:" + Ids.newId() + ":PAYMENT:AED");
    }

    /** Runs the statement as the app role in a transaction that is always rolled back. */
    private void assertDenied(String sql) {
        assertThatThrownBy(() -> new RlsProbe(dataSource).asTenant(null, db -> {
                    db.execute(sql);
                    return null;
                }))
                .as(sql)
                .rootCause()
                .hasMessageMatching("(?s).*(permission denied|must be owner).*");
    }
}
