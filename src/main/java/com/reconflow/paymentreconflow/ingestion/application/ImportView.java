package com.reconflow.paymentreconflow.ingestion.application;

import com.reconflow.paymentreconflow.ingestion.domain.ImportBatch;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import com.reconflow.paymentreconflow.ledger.SourceType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The batch as the API shows it (api-design.md §8.2): a DTO, never the entity, so internal columns
 * ({@code storage_key}, {@code content_hash}) can't leak. Nulls are included, so every response
 * has the same shape.
 *
 * @param storedErrors row errors actually stored; lower than {@code errorRows} only past the
 *     10,000-error cap (every bad row is still counted)
 */
public record ImportView(
        UUID id,
        SourceType sourceType,
        ImportBatchStatus status,
        String fileName,
        long sizeBytes,
        int totalRows,
        int validRows,
        int errorRows,
        long storedErrors,
        @Nullable String failureReason,
        UUID uploadedBy,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant completedAt) {

    public static ImportView of(ImportBatch b, long storedErrors) {
        return new ImportView(
                b.getId(),
                b.getSourceType(),
                b.getStatus(),
                b.getFileName(),
                b.getFileSizeBytes(),
                b.getTotalRows(),
                b.getValidRows(),
                b.getErrorRows(),
                storedErrors,
                b.getFailureReason(),
                b.getUploadedBy(),
                b.getCreatedAt(),
                b.getStartedAt(),
                b.getCompletedAt());
    }
}
