package com.reconflow.paymentreconflow.reporting.export;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A background export as {@code GET /api/v1/reports/exports/{id}} shows it (M8.4).
 *
 * @param status the job's: {@code QUEUED}, {@code RUNNING}, {@code DONE} or {@code FAILED}
 * @param fileUrl where to download the CSV, once {@code DONE}; null before
 */
public record ExportView(
        UUID id,
        UUID runId,
        String status,
        Instant createdAt,
        Instant updatedAt,
        @Nullable String fileUrl) {

    public boolean isTerminal() {
        return status.equals("DONE") || status.equals("FAILED");
    }
}
