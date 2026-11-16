package com.reconflow.paymentreconflow.shared.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Object storage settings ({@code reconflow.storage.*}), bound from the {@code S3_*} environment
 * variables. A typed record instead of scattered {@code @Value} strings: one place to see every
 * setting, and {@link Validated} fails startup on a blank region or bucket rather than on the
 * first upload. Endpoint and keys may be blank until FileStorage needs them (M5).
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
