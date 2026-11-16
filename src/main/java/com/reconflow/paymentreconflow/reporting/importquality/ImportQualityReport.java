package com.reconflow.paymentreconflow.reporting.importquality;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/reports/import-quality} (M8.2): which validation errors the files bring in,
 * for one batch or over time. Two counts that are easy to mix up are always labelled apart:
 * {@code errors} counts stored errors, and one row can have several (one per bad column);
 * {@code errorRows} counts distinct rows with at least one error.
 */
public record ImportQualityReport(
        Mode mode,
        @Nullable BatchQuality batch,
        @Nullable Period period,
        @Nullable List<Bucket> buckets) {

    public enum Mode {
        BATCH,
        OVER_TIME
    }

    public enum Granularity {
        DAY,
        WEEK,
        MONTH;

        /** The {@code date_trunc} field name: bound as a value, never concatenated into SQL. */
        String sqlField() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One batch. {@code totalRows}, {@code validRows} and {@code errorRows} are the batch's own
     * counters: every bad row, even past the 10,000 stored errors. {@code stored} and the
     * breakdowns count the stored errors only. A {@code FAILED} batch may hold partial errors,
     * hence {@code status}.
     */
    public record BatchQuality(
            UUID id,
            String sourceType,
            String status,
            String fileName,
            Instant createdAt,
            long totalRows,
            long validRows,
            long errorRows,
            Counts stored,
            List<CodeCount> byErrorCode,
            List<CodeColumnCount> byErrorCodeAndColumn) {}

    public record Counts(long errors, long errorRows) {}

    public record CodeCount(String errorCode, long errors, long errorRows) {}

    /** {@code column} is null for whole-row errors such as {@code MALFORMED_ROW}. */
    public record CodeColumnCount(
            String errorCode, @Nullable String column, long errors, long errorRows) {}

    /** {@code from} and {@code to} are UTC days, inclusive; buckets start at UTC midnight. */
    public record Period(
            LocalDate from,
            LocalDate to,
            Granularity granularity,
            @Nullable String sourceType) {}

    /**
     * One time bucket, by when the batches <em>arrived</em> (file quality, not transaction dates).
     * {@code totalRows} and {@code errorRows} are the batches' counters: the denominator for a rate.
     */
    public record Bucket(
            LocalDate bucket,
            long batches,
            long totalRows,
            long errorRows,
            Counts stored,
            List<CodeCount> byErrorCode) {}
}
