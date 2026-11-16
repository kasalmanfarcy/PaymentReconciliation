package com.reconflow.paymentreconflow.shared.ratelimit;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code reconflow.rate-limit.*} (M10.4).
 *
 * @param importUpload uploads (and import retries) per user per organization
 * @param runCreate run requests (and run retries) per user per organization
 * @param maxTrackedKeys bound on the bucket map: keys come from users, so it must not grow forever
 */
@Validated
@ConfigurationProperties("reconflow.rate-limit")
public record RateLimitProperties(
        @DefaultValue Limit importUpload,
        @DefaultValue Limit runCreate,
        @DefaultValue("100000") @Min(1) long maxTrackedKeys) {

    /** A token bucket: {@code capacity} requests in a burst, refilled to capacity every {@code period}. */
    public record Limit(
            @DefaultValue("10") @Min(1) int capacity,
            @DefaultValue("1m") Duration period) {}

    Limit of(RateLimitPolicy policy) {
        return Map.of(RateLimitPolicy.IMPORT_UPLOAD, importUpload, RateLimitPolicy.RUN_CREATE, runCreate)
                .get(policy);
    }
}
