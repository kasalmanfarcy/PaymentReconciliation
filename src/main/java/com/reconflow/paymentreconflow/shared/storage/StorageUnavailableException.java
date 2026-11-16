package com.reconflow.paymentreconflow.shared.storage;

/**
 * Object storage didn't answer, or answered with a 5xx: an outage, not a problem with the request.
 * Transient: an upload gets {@code 503 storage-unavailable} with {@code Retry-After} (the client can
 * simply try again), and a job is re-queued with backoff. Missing objects are
 * {@link StoredFileMissingException} instead, which no retry fixes.
 */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
