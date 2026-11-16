package com.reconflow.paymentreconflow.ingestion.storage;

import com.reconflow.paymentreconflow.shared.config.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** {@link FileStorage} on the S3 API (AWS SDK v2): MinIO locally and in tests, S3 in production. */
@Component
class S3FileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

    private final S3Client s3;
    private final String bucket;

    S3FileStorage(S3Client s3, StorageProperties props) {
        this.s3 = s3;
        this.bucket = props.bucket();
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        // Streams the content with its known length: never buffers the whole file in heap.
        s3.putObject(
                r -> r.bucket(bucket).key(key).contentType(contentType), RequestBody.fromInputStream(content, size));
    }

    @Override
    public void download(String key, Path target) {
        try (ResponseInputStream<GetObjectResponse> in =
                s3.getObject(r -> r.bucket(bucket).key(key))) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchKeyException e) {
            throw new StoredFileMissingException(key);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(r -> r.bucket(bucket).key(key));
    }

    @Override
    public void deleteQuietly(String key) {
        try {
            delete(key);
        } catch (SdkException e) {
            // An orphan costs a few MB until OrphanFileSweeper removes it; failing the request
            // over it would be worse.
            log.warn("Could not delete {}; the orphan sweeper will remove it", key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(r -> r.bucket(bucket).key(key));
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false; // HEAD responses have no body, so some servers send a bare 404
            }
            throw e;
        }
    }

    @Override
    public List<String> topLevelPrefixes() {
        return s3.listObjectsV2Paginator(r -> r.bucket(bucket).delimiter("/")).commonPrefixes().stream()
                .map(CommonPrefix::prefix)
                .map(p -> p.endsWith("/") ? p.substring(0, p.length() - 1) : p)
                .toList();
    }

    @Override
    public void forEach(String prefix, Consumer<StoredObject> action) {
        // The paginator fetches 1,000 keys per request, lazily.
        s3.listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix)).contents().stream()
                .map(o -> new StoredObject(o.key(), o.lastModified()))
                .forEach(action);
    }
}
