package com.reconflow.paymentreconflow;

import com.reconflow.paymentreconflow.support.TestDatabase;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Starts a throwaway PostgreSQL and MinIO for tests; tests need no {@code .env} and nothing
 * installed locally.
 *
 * <p>The database is set up the way production is (M10.7): the committed
 * {@code infra/db/bootstrap-roles.sql} creates {@code reconflow_migrator} and {@code reconflow_app},
 * Flyway connects as the migrator ({@link #flywayConnection}) and the application as the app role
 * ({@link #appConnection}). Not {@code @ServiceConnection}, which would hand the application the
 * container's superuser: superusers bypass row-level security, so every RLS and grant test would
 * pass while proving nothing. The superuser is available to test code only, via
 * {@link TestDatabase#superuser()}.
 *
 * <p>MinIO has no service connection either, so {@link #storageProperties} points
 * {@code reconflow.storage.*} at it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Pinned to a specific tag so every machine and CI run tests against the same engine.
    static final DockerImageName POSTGRES = DockerImageName.parse("postgres:16.4-alpine");

    // The same image and tag as compose.yaml. The official minio/minio image is no longer
    // published; pgsty/minio is a community build of the same server, so tell Testcontainers it may
    // stand in for minio/minio.
    static final DockerImageName MINIO =
            DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z").asCompatibleSubstituteFor("minio/minio");

    public static final String BUCKET = "reconflow-imports";

    @Bean
    PostgreSQLContainer postgres() throws IOException, InterruptedException {
        // fsync=off is Testcontainers' default command; pg_stat_statements is preloaded for M9.7's
        // query counter (support.QueryCounter), which counts every statement the server ran,
        // Hibernate's and JdbcClient's alike, with no proxy around the DataSource.
        PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES)
                .withDatabaseName("paymentreconflow")
                .withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("infra/db/bootstrap-roles.sql")),
                        "/reconflow/bootstrap-roles.sql");
        postgres.start();
        return postgres;
    }

    /**
     * Runs the real bootstrap script with psql, as the container's superuser, before any connection
     * from the application exists: if the committed script is broken, every integration test fails.
     */
    @Bean
    TestDatabase testDatabase(PostgreSQLContainer postgres) throws IOException, InterruptedException {
        String appPassword = "app-" + UUID.randomUUID();
        String migratorPassword = "migrator-" + UUID.randomUUID();
        ExecResult roles = postgres.execInContainer(
                "psql",
                "-U",
                postgres.getUsername(),
                "-d",
                postgres.getDatabaseName(),
                "-v",
                "ON_ERROR_STOP=1",
                "-v",
                "migrator_pw=" + migratorPassword,
                "-v",
                "app_pw=" + appPassword,
                "-f",
                "/reconflow/bootstrap-roles.sql");
        if (roles.getExitCode() != 0) {
            throw new IllegalStateException("bootstrap-roles.sql failed: " + roles.getStderr() + roles.getStdout());
        }
        // Test-only extension behind support.QueryCounter. Creating it needs a superuser, so it's
        // done here rather than by Flyway, which now runs as the (non-superuser) migrator.
        ExecResult extension = postgres.execInContainer(
                "psql",
                "-U",
                postgres.getUsername(),
                "-d",
                postgres.getDatabaseName(),
                "-v",
                "ON_ERROR_STOP=1",
                "-c",
                "CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        if (extension.getExitCode() != 0) {
            throw new IllegalStateException("pg_stat_statements: " + extension.getStderr());
        }
        return new TestDatabase(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), appPassword, migratorPassword);
    }

    /** The application's DataSource: the unprivileged app role, as in production. */
    @Bean
    JdbcConnectionDetails appConnection(TestDatabase database) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return TestDatabase.APP_ROLE;
            }

            @Override
            public String getPassword() {
                return database.appPassword();
            }

            @Override
            public String getJdbcUrl() {
                return database.jdbcUrl();
            }
        };
    }

    /** Flyway's own connection, as the schema owner. */
    @Bean
    FlywayConnectionDetails flywayConnection(TestDatabase database) {
        return new FlywayConnectionDetails() {
            @Override
            public String getUsername() {
                return TestDatabase.MIGRATOR_ROLE;
            }

            @Override
            public String getPassword() {
                return database.migratorPassword();
            }

            @Override
            public String getJdbcUrl() {
                return database.jdbcUrl();
            }
        };
    }

    /**
     * Started here (not lazily) so the bucket can be created before anything uploads. Production
     * never creates buckets from application code; compose's minio-init does it locally, and this
     * does it for tests, with the mc client the image ships.
     */
    @Bean
    MinIOContainer minio() throws IOException, InterruptedException {
        MinIOContainer minio = new MinIOContainer(MINIO);
        minio.start();
        ExecResult result = minio.execInContainer(
                "sh",
                "-c",
                "mc alias set local http://localhost:9000 %s %s && mc mb --ignore-existing local/%s"
                        .formatted(minio.getUserName(), minio.getPassword(), BUCKET));
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Could not create the test bucket: " + result.getStderr());
        }
        return minio;
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("reconflow.storage.endpoint", minio::getS3URL);
            registry.add("reconflow.storage.access-key", minio::getUserName);
            registry.add("reconflow.storage.secret-key", minio::getPassword);
            registry.add("reconflow.storage.bucket", () -> BUCKET);
        };
    }
}
