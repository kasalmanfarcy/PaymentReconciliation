package com.reconflow.paymentreconflow.reporting.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/reports/reconciliation} (M8.1): one response that answers the four questions
 * of the README for a run or a date range. Which match? {@code matched}. Which don't?
 * {@code unmatchedLedger}, {@code unmatchedGateway}, {@code mismatches}, {@code duplicates},
 * {@code currencyMismatches}. Why? {@code exceptionsByReason}. Who resolved them?
 * {@code resolution}.
 *
 * <p>Counts are <em>cases</em>, as in the run summary (api-design.md §8.10). Money is a
 * {@link BigDecimal}, written as a JSON string by the shared MoneySerializer, always next to
 * {@code currency}.
 *
 * @param excluded records excluded by rules P4/P5; run mode only (it has no anchor day), null in
 *     date-range mode
 * @param runsUsed which run was authoritative for which days
 */
public record ReconciliationReport(
        Scope scope,
        String currency,
        CountAndAmount matched,
        CountAndAmount unmatchedLedger,
        CountAndAmount unmatchedGateway,
        MismatchSummary mismatches,
        Count duplicates,
        Count currencyMismatches,
        @Nullable Count excluded,
        List<ReasonCount> exceptionsByReason,
        ResolutionSummary resolution,
        List<RunRef> runsUsed,
        Coverage coverage) {

    public enum Mode {
        RUN,
        DATE_RANGE
    }

    /** What was asked for: one run (with its period as from/to), or a date range. */
    public record Scope(Mode mode, @Nullable UUID runId, LocalDate from, LocalDate to) {}

    public record Count(long count) {}

    public record CountAndAmount(long count, BigDecimal amount) {}

    /** {@code netDifference} is the sum of {@code gateway − ledger} over the mismatch cases. */
    public record MismatchSummary(long count, BigDecimal netDifference) {}

    /**
     * The cases of one type and reason code, by status. {@code amount} is the sum of the cases'
     * amount at stake; {@code amountDifference} the sum of their {@code gateway − ledger}
     * (non-zero for {@code AMOUNT_MISMATCH} only).
     */
    public record ReasonCount(
            String type,
            @Nullable String reasonCode,
            long count,
            long open,
            long inReview,
            long resolved,
            BigDecimal amount,
            BigDecimal amountDifference) {}

    public record ResolutionSummary(long open, long inReview, long resolved, List<ResolverCount> byResolver) {}

    public record ResolverCount(UUID userId, String displayName, long resolved) {}

    public record RunRef(UUID runId, @Nullable Instant completedAt, List<LocalDate> days) {}

    /**
     * Days asked for versus days some completed run covered. A day no run covered is listed in
     * {@code missingDays}, never reported as a day with zero activity.
     */
    public record Coverage(long requestedDays, long coveredDays, List<LocalDate> missingDays) {}
}
