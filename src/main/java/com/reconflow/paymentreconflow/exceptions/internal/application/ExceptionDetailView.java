package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ResolutionCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One case with everything an investigator needs on one screen (api-design.md §8.14): both sides'
 * records with raw and normalized values, the runs that saw it, notes and status history, oldest
 * first. {@code version} is also the response's {@code ETag}.
 */
public record ExceptionDetailView(
        UUID id,
        ExceptionType type,
        ExceptionStatus status,
        @Nullable String reasonCode,
        String discrepancyKey,
        String reference,
        String currency,
        BigDecimal amount,
        @Nullable BigDecimal amountDifference,
        @Nullable UserRef assignedTo,
        @Nullable Resolution resolution,
        UUID runId,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Records records,
        List<RunRef> runs,
        List<NoteView> notes,
        List<HistoryView> history) {

    /** Set exactly while RESOLVED (V1's CHECK). */
    public record Resolution(ResolutionCode code, @Nullable String note, UserRef resolvedBy, Instant resolvedAt) {}

    /** The linked records, ledger and gateway side by side. */
    public record Records(List<RecordView> ledger, List<RecordView> gateway) {}

    /**
     * A linked transaction record. {@code rawReference} and {@code rawValues} are exactly what the
     * file said: "the ledger has {@code pay-103 } with a trailing space" is often the whole story.
     */
    public record RecordView(
            UUID id,
            String rawReference,
            String normalizedReference,
            BigDecimal amount,
            String currency,
            String transactionType,
            String status,
            LocalDate transactionDate,
            @Nullable Instant transactionAt,
            UUID batchId,
            long sourceRowNumber,
            boolean duplicateInSource,
            JsonNode rawValues) {}

    /** A run that observed the case; {@code created} is true for the run that raised it. */
    public record RunRef(UUID runId, boolean created, Instant observedAt) {}

    public record NoteView(UUID id, UserRef author, String body, Instant createdAt) {}

    /** One status change; {@code actor} is null for the engine's creation row. */
    public record HistoryView(
            UUID id,
            @Nullable ExceptionStatus from,
            ExceptionStatus to,
            @Nullable String reason,
            @Nullable ResolutionCode resolutionCode,
            @Nullable String resolutionNote,
            @Nullable UserRef actor,
            Instant at) {}
}
