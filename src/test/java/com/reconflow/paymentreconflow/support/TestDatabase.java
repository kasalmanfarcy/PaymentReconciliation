package com.reconflow.paymentreconflow.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The test database and its three roles (M10.7), mirroring production:
 *
 * <ul>
 *   <li>{@code reconflow_app}: the application's own DataSource. Not the owner, not a superuser, no
 *       BYPASSRLS, so row-level security and the grants of V10 apply to everything the app does in a
 *       test, exactly as in production.</li>
 *   <li>{@code reconflow_migrator}: Flyway's connection; owns the schema.</li>
 *   <li>the container's superuser: <em>test code only</em>, through {@link #superuser()}. Fixtures
 *       are written and outcomes checked from outside the application, like a DBA would, so a test
 *       can insert two tenants' rows and see both. It is never handed to the application.</li>
 * </ul>
 *
 * <p>Passwords are generated per run; nothing here is a committed secret.
 */
public final class TestDatabase implements AutoCloseable {

    public static final String APP_ROLE = "reconflow_app";
    public static final String MIGRATOR_ROLE = "reconflow_migrator";

    private final String jdbcUrl;
    private final String appPassword;
    private final String migratorPassword;
    private final HikariDataSource superuserPool;
    private final JdbcTemplate superuser;

    public TestDatabase(
            String jdbcUrl,
            String superuserName,
            String superuserPassword,
            String appPassword,
            String migratorPassword) {
        this.jdbcUrl = jdbcUrl;
        this.appPassword = appPassword;
        this.migratorPassword = migratorPassword;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(superuserName);
        config.setPassword(superuserPassword);
        config.setMaximumPoolSize(4);
        config.setPoolName("test-superuser");
        this.superuserPool = new HikariDataSource(config);
        this.superuser = new JdbcTemplate(superuserPool);
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String appPassword() {
        return appPassword;
    }

    public String migratorPassword() {
        return migratorPassword;
    }

    /** Superuser access for fixtures and assertions; bypasses RLS and every grant. */
    public JdbcTemplate superuser() {
        return superuser;
    }

    @Override
    public void close() {
        superuserPool.close();
    }
}
