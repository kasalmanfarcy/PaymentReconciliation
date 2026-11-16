package com.reconflow.paymentreconflow.shared.db;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Who applies migrations (M11.5). Deployed, they run once per deploy as their own step (the
 * {@code migrate} role, with the migrator's credentials) before any new instance starts. The api and
 * worker then only <em>validate</em>: an instance started against a database with a pending or
 * altered migration refuses to start, with Flyway's message naming the version, instead of running
 * new code on an old schema. Validation is a read of {@code flyway_schema_history}, which the
 * application role may do (V10), so the running app never needs DDL rights or the migrator's
 * password.
 *
 * <p>{@code reconflow.flyway.strategy}: {@code migrate} by default (local and test keep
 * migrate-on-startup, for convenience), {@code validate} in application-prod.yml, back to
 * {@code migrate} in application-migrate.yml.
 */
@Configuration(proxyBeanMethods = false)
class FlywayStrategyConfig {

    @Bean
    @ConditionalOnProperty(name = "reconflow.flyway.strategy", havingValue = "validate")
    FlywayMigrationStrategy validateOnlyMigrationStrategy() {
        return validateOnly();
    }

    /** Fails if a migration is pending, missing or its checksum differs; never changes the schema. */
    static FlywayMigrationStrategy validateOnly() {
        return Flyway::validate;
    }
}
