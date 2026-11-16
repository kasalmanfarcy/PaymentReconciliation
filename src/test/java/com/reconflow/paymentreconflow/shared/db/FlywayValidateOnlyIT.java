package com.reconflow.paymentreconflow.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestDatabase;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M11.5: deployed api and worker instances only validate the schema, as the application role. Run on
 * a database of its own (same server, so the two roles exist), migrated by the migrator like the
 * deploy's migrate step does:
 *
 * <ul>
 *   <li>one migration behind, an instance refuses to start, and the message names the version;
 *   <li>once the migrate step has run, the application role can validate on its own: reading
 *       {@code flyway_schema_history} is all it needs, so it never holds DDL rights.
 * </ul>
 */
class FlywayValidateOnlyIT extends AbstractIntegrationTest {

    @Autowired
    TestDatabase database;

    private String name;
    private String url;

    @BeforeEach
    void createDatabase() {
        name = "validate_only_" + UUID.randomUUID().toString().replace("-", "");
        // Owned by the migrator, as bootstrap-roles.sql leaves a real database.
        jdbc.execute("CREATE DATABASE " + name + " OWNER " + TestDatabase.MIGRATOR_ROLE);
        url = database.jdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    @AfterEach
    void dropDatabase() {
        jdbc.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
    }

    @Test
    void anInstanceRefusesToStartWhileAMigrationIsPendingAndValidatesOnceItIsApplied() {
        MigrationInfo[] all = asMigrator().load().info().all();
        MigrationVersion latest = all[all.length - 1].getVersion();
        String previous = String.valueOf(Integer.parseInt(latest.getVersion()) - 1);
        asMigrator().target(previous).load().migrate();

        assertThatThrownBy(() -> FlywayStrategyConfig.validateOnly().migrate(asApp()))
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("not applied")
                .hasMessageContaining(latest.getVersion());

        asMigrator().load().migrate(); // the deploy's migrate step

        FlywayStrategyConfig.validateOnly().migrate(asApp());
        assertThat(asApp().info().pending()).isEmpty();
        // Validation changed nothing: the history is exactly what the migrator wrote.
        assertThat(asApp().info().applied()).hasSize(Integer.parseInt(latest.getVersion()));
    }

    private FluentConfiguration asMigrator() {
        return Flyway.configure()
                .dataSource(url, TestDatabase.MIGRATOR_ROLE, database.migratorPassword())
                .locations("classpath:db/migration");
    }

    private Flyway asApp() {
        return Flyway.configure()
                .dataSource(url, TestDatabase.APP_ROLE, database.appPassword())
                .locations("classpath:db/migration")
                .load();
    }
}
