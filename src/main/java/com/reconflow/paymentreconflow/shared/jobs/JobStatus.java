package com.reconflow.paymentreconflow.shared.jobs;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A job as a status endpoint may show it (M8.4's export status). No {@code last_error} and no
 * worker id: those are for operators, and an error text can carry internal detail.
 *
 * @param status {@code QUEUED}, {@code RUNNING}, {@code DONE} or {@code FAILED}
 */
public record JobStatus(
        UUID id,
        UUID organizationId,
        String type,
        String status,
        Map<String, Object> payload,
        Instant createdAt,
        Instant updatedAt) {

    public boolean isTerminal() {
        return status.equals("DONE") || status.equals("FAILED");
    }
}
