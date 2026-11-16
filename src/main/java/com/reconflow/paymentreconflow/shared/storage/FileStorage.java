package com.reconflow.paymentreconflow.shared.storage;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where files live (M5.8): S3-compatible object storage, MinIO locally and in tests. Not the local
 * disk: with two or more instances, the API node that received an upload and the worker that
 * processes it are different machines (scalability.md D3). Holds the original uploads
 * ({@code ingestion}) and large exception exports ({@code reporting}, M8.4), which is why it lives
 * in {@code shared} since M8: both modules use it, and neither may depend on the other.
 *
 * <p>Keys are built only from server-generated IDs ({@code {orgId}/imports/{batchId}/original.csv},
 * {@code {orgId}/exports/{jobId}.csv}), never from a user-supplied name, so a name like
 * {@code ../../etc/passwd} can't reach a path.
 */
public interface FileStorage {

    record StoredObject(String key, Instant lastModified) {}

    void put(String key, InputStream content, long size, String contentType);

    /**
     * Copies the object to a local file. The worker parses from that file rather than from the
     * network stream: a stream held open for the minutes a large import takes is fragile.
     *
     * @throws StoredFileMissingException if there is no such object (a permanent failure)
     */
    void download(String key, Path target);

    /**
     * Streams the object into {@code target} without buffering it in memory (M8.4's export
     * download). Doesn't close {@code target}.
     *
     * @throws StoredFileMissingException if there is no such object
     */
    void copyTo(String key, OutputStream target);

    void delete(String key);

    /** Best effort: logs instead of throwing. For clean-up after a failed or lost upload race. */
    void deleteQuietly(String key);

    boolean exists(String key);

    /** The first path segments under the bucket root ({@code {orgId}/}), without the slash. */
    List<String> topLevelPrefixes();

    /** Every object whose key starts with {@code prefix}, page by page, without loading all keys. */
    void forEach(String prefix, Consumer<StoredObject> action);
}
