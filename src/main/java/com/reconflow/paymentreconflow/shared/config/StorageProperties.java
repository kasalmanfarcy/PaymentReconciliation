package com.reconflow.paymentreconflow.shared.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Object storage settings ({@code reconflow.storage.*}), bound from the {@code S3_*} environment
 * variables. A typed record instead of scattered {@code @Value} strings: one place to see every
 * setting, and {@link Validated} fails startup on a blank region or bucket rather than on the
 * first upload. A blank endpoint means AWS's own S3 endpoints (otherwise path-style requests to
 * the endpoint, as MinIO needs); blank keys mean the SDK's default credential chain (an IAM role in
 * production) instead of static keys. Used by shared.storage's S3 FileStorage (M5.8; imports and M8 exports).
 */
@Validated
@ConfigurationProperties("reconflow.storage")
public record StorageProperties(
        String endpoint, @NotBlank String region, @NotBlank String bucket, String accessKey, String secretKey) {

    /** Keep the secret out of logs and actuator output if the record is ever printed. */
    @Override
    public String toString() {
        return "StorageProperties[endpoint=%s, region=%s, bucket=%s, accessKey=%s, secretKey=***]"
                .formatted(endpoint, region, bucket, accessKey);
    }
}
