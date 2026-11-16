package com.reconflow.paymentreconflow.reporting.ageing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/v1/reports/exception-ageing} (M8.3): the cases still being worked ({@code OPEN}
 * and {@code IN_REVIEW}) by age bucket, overall and per assignee, with the amount at stake. Age
 * runs from {@code created_at}, when the case was first raised: a reopened case keeps its age,
 * because the discrepancy has existed that long.
 *
 * <p>Every list of buckets has all five, in order, zeros included, so a client can draw it as is.
 * Amounts are per currency: adding AED to USD gives a meaningless number.
 *
 * @param asOf the database's {@code now()} the ages were measured against (one instant for the
 *     whole report)
 */
public record ExceptionAgeingReport(
        Instant asOf, Filters filters, List<BucketTotal> buckets, List<AssigneeRow> byAssignee) {

    /** The five buckets, lower bound inclusive: exactly one day old is {@code 1-3d}. */
    public static final List<String> BUCKETS = List.of("0-1d", "1-3d", "3-7d", "7-30d", "30d+");

    public record Filters(
            List<String> type,
            @Nullable String assignedTo,
            @Nullable String currency) {}

    /**
     * {@code amountAtStake}: the sum of the cases' {@code amount} per currency, the amount at stake
     * as api-design.md §8.20 defines it (the ledger amount, else the gateway amount; for a duplicate,
     * its smallest amount).
     */
    public record BucketTotal(String bucket, long cases, List<Money> amountAtStake) {}

    public record Money(String currency, BigDecimal amount) {}

    /** {@code assignee} is null for the unassigned cases, listed first: usually the row that matters. */
    public record AssigneeRow(@Nullable Assignee assignee, long cases, List<BucketTotal> buckets) {}

    public record Assignee(UUID id, String displayName) {}
}
