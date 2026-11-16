package com.reconflow.paymentreconflow.ingestion.validation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A row that passed every check, with its values parsed into types but not yet normalized: the
 * normalizer only ever sees fully valid data.
 *
 * @param transactionAt the file's timestamp with its own offset, if the row has one
 * @param transactionDate the file's calendar date (ledger only); ignored when transactionAt exists
 */
public record ValidatedRow(
        int number,
        String rawReference,
        BigDecimal amount,
        String currency,
        String status,
        @Nullable String transactionType,
        @Nullable OffsetDateTime transactionAt,
        @Nullable LocalDate transactionDate,
        Map<String, String> rawValues) {}
