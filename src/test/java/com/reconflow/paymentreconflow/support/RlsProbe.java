package com.reconflow.paymentreconflow.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Runs SQL the way the application will once M10.7 gives it its own role: not a superuser, not
 * the table owner, no BYPASSRLS. Testcontainers connects as a superuser, and superusers bypass
 * row-level security entirely, so an RLS test that doesn't switch roles proves nothing.
 *
 * <p>Each call is one transaction on one connection: {@code SET LOCAL ROLE rls_probe}, optionally
 * {@code set_config('app.org_id', ..., true)}, the work, then ROLLBACK (nothing persists).
 */
public final class RlsProbe {

    public static final String ROLE = "rls_probe";

    @FunctionalInterface
    public interface Work<T> {
        T run(JdbcTemplate jdbc) throws Exception;
    }

    private final DataSource dataSource;

    public RlsProbe(DataSource dataSource, JdbcTemplate superuser) {
        this.dataSource = dataSource;
        superuser.execute("""
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rls_probe') THEN
                    CREATE ROLE rls_probe NOLOGIN NOSUPERUSER NOBYPASSRLS;
                  END IF;
                END $$""");
        superuser.execute("GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA public TO rls_probe");
    }

    /** As rls_probe, with {@code org} bound as the database tenant (or none). */
    public <T> T asTenant(@Nullable UUID org, Work<T> work) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.execute("SET LOCAL ROLE " + ROLE);
                if (org != null) {
                    jdbc.queryForObject("SELECT set_config('app.org_id', ?, true)", String.class, org.toString());
                }
                return work.run(jdbc);
            } finally {
                connection.rollback();
            }
        }
    }

    /** Runs {@code work} on one pooled connection across several of its transactions. */
    public <T> T onOneConnection(Work<T> work) throws SQLException, Exception {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
        }
    }
}
