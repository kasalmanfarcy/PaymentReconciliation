package com.reconflow.paymentreconflow.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One validated, normalized row on its way into {@code transaction_record}: a small record rather
 * than an entity, so a 5,000-row chunk costs 5,000 small objects and no persistence-context
 * snapshots.
 *
 * @param id UUIDv7 generated in Java (ADR 0010): no sequence round trip per row
 * @param sourceRowNumber the row in its file; with {@code batchId} and the date, the retry guard key
 * @param amount scale 4, from {@code new BigDecimal(String)}, never a double (rule N6)
 * @param transactionDate the UTC date of {@code transactionAt} when present (N7), else the file's (N8)
 * @param rawValues the original row: original header text to original string (N11)
 */
public record NewTransactionRecord(
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
        Map<String, String> rawValues) {}
