package com.reconflow.paymentreconflow.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Runs plain SQL on the application's own DataSource, outside the application's code, to test the
 * database layer of tenant isolation by itself. Since M10.7 that DataSource connects as
 * {@code reconflow_app}: not a superuser, not the owner, no BYPASSRLS. (Before, this class had to
 * {@code SET ROLE} to a stand-in role, because the app connected as a superuser and superusers bypass
 * row-level security.) The constructor checks those three facts, so a test built on this can't pass
 * against a privileged connection.
 *
 * <p>Each call is one transaction on one connection: optionally
 * {@code set_config('app.org_id', ..., true)}, the work, then ROLLBACK (nothing persists).
 */
public final class RlsProbe {

    @FunctionalInterface
    public interface Work<T> {
        T run(JdbcTemplate jdbc) throws Exception;
    }

    private final DataSource appDataSource;

    public RlsProbe(DataSource appDataSource) {
        this.appDataSource = appDataSource;
        Map<String, Object> role = new JdbcTemplate(appDataSource).queryForMap("""
                SELECT current_user AS name, r.rolsuper, r.rolbypassrls,
                       pg_get_userbyid(c.relowner) AS owner_of_import_batch
                  FROM pg_roles r, pg_class c
                 WHERE r.rolname = current_user AND c.oid = 'import_batch'::regclass""");
        assertThat(role.get("name")).isEqualTo(TestDatabase.APP_ROLE);
        assertThat(role.get("rolsuper")).isEqualTo(false);
        assertThat(role.get("rolbypassrls")).isEqualTo(false);
        assertThat(role.get("owner_of_import_batch")).isNotEqualTo(TestDatabase.APP_ROLE);
    }

    /** As the application role, with {@code org} bound as the database tenant (or none). */
    public <T> T asTenant(@Nullable UUID org, Work<T> work) throws Exception {
        try (Connection connection = appDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                if (org != null) {
                    jdbc.queryForObject("SELECT set_config('app.org_id', ?, true)", String.class, org.toString());
                }
                return work.run(jdbc);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    /** Runs {@code work} on one pooled application connection across several of its transactions. */
    public <T> T onOneConnection(Work<T> work) throws SQLException, Exception {
        try (Connection connection = appDataSource.getConnection()) {
            return work.run(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
        }
    }
}
