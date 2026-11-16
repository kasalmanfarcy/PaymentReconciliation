package com.reconflow.paymentreconflow.shared.storage;

import com.reconflow.paymentreconflow.shared.config.StorageProperties;
import java.time.Duration;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@code storage} health (M9.11): can we reach the bucket? Part of the <em>readiness</em> group
 * only, like the database: without storage an API node can't accept uploads and a worker can't read
 * them, so it should get no traffic, but restarting it (liveness) wouldn't bring storage back.
 * One cheap {@code HEAD bucket}, with a short timeout so a hung storage endpoint can't push the probe
 * past the orchestrator's own timeout.
 */
@Component("storage")
class StorageHealthIndicator extends AbstractHealthIndicator {

    static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final S3Client s3;
    private final String bucket;

    StorageHealthIndicator(S3Client s3, StorageProperties props) {
        super("Object storage health check failed");
        this.s3 = s3;
        this.bucket = props.bucket();
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        s3.headBucket(r -> r.bucket(bucket).overrideConfiguration(c -> c.apiCallTimeout(TIMEOUT)));
        builder.up();
    }
}
