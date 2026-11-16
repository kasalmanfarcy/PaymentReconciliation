package com.reconflow.paymentreconflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a throwaway PostgreSQL for tests. {@link ServiceConnection} replaces the
 * datasource URL, username and password with the container's, so tests need no
 * {@code .env} and no locally installed database.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Pinned to a specific tag so every machine and CI run tests against the same engine.
    static final DockerImageName POSTGRES = DockerImageName.parse("postgres:16.4-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES);
    }
}
