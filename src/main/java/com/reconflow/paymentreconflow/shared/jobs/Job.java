package com.reconflow.paymentreconflow.shared.jobs;

import java.util.Map;
import java.util.UUID;

/**
 * A claimed job, as its handler sees it.
 *
 * @param attempts how many times it has been claimed, this time included (1 on the first run)
 * @param workerId who holds the lease: {@code host:pid:slot}; every status write is fenced on it
 */
public record Job(
        UUID id,
        UUID organizationId,
        String type,
        Map<String, Object> payload,
        int attempts,
        int maxAttempts,
        String workerId) {

    /** A payload value that must be a UUID string, e.g. {@code batchId}. */
    public UUID uuid(String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new PermanentJobFailure("job payload has no " + key);
        }
        return UUID.fromString(value.toString());
    }
}
