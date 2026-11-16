package com.reconflow.paymentreconflow;

import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a throwaway PostgreSQL and MinIO for tests. {@link ServiceConnection} replaces the
 * datasource URL, username and password with the container's; MinIO has no service connection, so
 * {@link #storageProperties} points {@code reconflow.storage.*} at it. Tests need no {@code .env}
 * and nothing installed locally.
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
    @ServiceConnection
    PostgreSQLContainer postgres() {
        // fsync=off is Testcontainers' default command; pg_stat_statements is preloaded for M9.7's
        // query counter (support.QueryCounter), which counts every statement the server ran,
        // Hibernate's and JdbcClient's alike, with no proxy around the DataSource.
        return new PostgreSQLContainer(POSTGRES)
                .withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements");
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
