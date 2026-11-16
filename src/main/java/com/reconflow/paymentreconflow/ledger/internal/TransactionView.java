package com.reconflow.paymentreconflow.ledger.internal;

import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.ledger.TransactionStatus;
import com.reconflow.paymentreconflow.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One normalized transaction in search results. {@code amount} is serialized as a plain-notation
 * string at scale 4 next to its currency (api-design.md §1). {@code raw_values} is left out of the
 * list on purpose: it is the largest column, and nobody scans a page of raw rows.
 */
public record TransactionView(
        UUID id,
        UUID batchId,
        SourceType sourceType,
        int sourceRowNumber,
        String rawReference,
        String normalizedReference,
        BigDecimal amount,
        String currency,
        TransactionType transactionType,
        TransactionStatus status,
        LocalDate transactionDate,
        @Nullable Instant transactionAt,
        boolean duplicateInSource,
        Instant createdAt) {}
