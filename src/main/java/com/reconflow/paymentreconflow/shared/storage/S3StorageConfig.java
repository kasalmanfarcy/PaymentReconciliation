package com.reconflow.paymentreconflow.shared.storage;

import com.reconflow.paymentreconflow.shared.config.StorageProperties;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * The S3 client, from {@code reconflow.storage.*} (the {@code S3_*} environment variables; never a
 * committed value). Building the client opens no connection, so a misconfiguration shows up on the
 * first upload; the bucket itself is created by infrastructure (compose's {@code minio-init}), never
 * by application code.
 */
@Configuration(proxyBeanMethods = false)
class S3StorageConfig {

    @Bean(destroyMethod = "close")
    S3Client s3Client(StorageProperties props) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(props.region()))
                .credentialsProvider(credentials(props))
                // Checksums only where S3 requires them: S3-compatible servers (MinIO) don't all
                // accept the SDK's newer default trailing checksums.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (props.endpoint() != null && !props.endpoint().isBlank()) {
            // MinIO (or any non-AWS endpoint) needs path-style URLs: http://host/bucket/key.
            builder.endpointOverride(URI.create(props.endpoint())).forcePathStyle(true);
        }
        return builder.build();
    }

    /** Static keys when configured; otherwise the SDK's chain (an IAM role in production). */
    private static AwsCredentialsProvider credentials(StorageProperties props) {
        if (props.accessKey() != null && !props.accessKey().isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
