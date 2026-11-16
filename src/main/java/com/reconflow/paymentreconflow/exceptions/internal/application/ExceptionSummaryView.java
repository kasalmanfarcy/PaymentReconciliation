package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One row of the work queue (api-design.md §8.13). {@code version} lets a client act without a GET. */
public record ExceptionSummaryView(
        UUID id,
        ExceptionType type,
        ExceptionStatus status,
        @Nullable String reasonCode,
        String reference,
        String currency,
        BigDecimal amount,
        @Nullable BigDecimal amountDifference,
        @Nullable UserRef assignedTo,
        UUID runId,
        long version,
        Instant createdAt,
        Instant updatedAt) {}
