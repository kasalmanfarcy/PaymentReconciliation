package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRun;
import com.reconflow.paymentreconflow.reconciliation.domain.RunStatus;
import com.reconflow.paymentreconflow.reconciliation.domain.RunSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A run as the API shows it (api-design.md §8.9). {@code summary} is null until COMPLETED. Counts
 * are cases, not records; {@code excluded} is records (rules P4/P5). Money is serialized as strings
 * by the shared MoneySerializer, always next to the run's {@code currency}.
 */
public record RunView(
        UUID id,
        RunStatus status,
        LocalDate periodStart,
        LocalDate periodEnd,
        String currency,
        SourceType leftSource,
        SourceType rightSource,
        Progress progress,
        @Nullable Summary summary,
        @Nullable String failureReason,
        UUID triggeredBy,
        Instant createdAt,
        @Nullable Instant startedAt,
        @Nullable Instant completedAt) {

    public record Progress(@Nullable LocalDate lastCompletedDate) {}

    public record Summary(
            Amount matched,
            Difference amountMismatch,
            Amount unmatchedLeft,
            Amount unmatchedRight,
            Count duplicates,
            Count currencyMismatches,
            Count excluded) {}

    public record Amount(int count, BigDecimal amount) {}

    public record Difference(int count, BigDecimal netDifference) {}

    public record Count(int count) {}

    public static RunView of(ReconciliationRun run) {
        return new RunView(
                run.getId(),
                run.getStatus(),
                run.getPeriodStart(),
                run.getPeriodEnd(),
                run.getCurrency(),
                run.getLeftSource(),
                run.getRightSource(),
                new Progress(run.getLastCompletedDate()),
                run.getStatus() == RunStatus.COMPLETED ? summary(run.summary()) : null,
                run.getFailureReason(),
                run.getTriggeredBy(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt());
    }

    private static Summary summary(RunSummary s) {
        return new Summary(
                new Amount(s.matchedCount(), s.matchedAmount()),
                new Difference(s.mismatchCount(), s.mismatchNetDifference()),
                new Amount(s.unmatchedLeftCount(), s.unmatchedLeftAmount()),
                new Amount(s.unmatchedRightCount(), s.unmatchedRightAmount()),
                new Count(s.duplicateCount()),
                new Count(s.currencyMismatchCount()),
                new Count(s.excludedCount()));
    }
}
