package com.reconflow.paymentreconflow.reporting.ageing;

import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingQueries.Filter;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingQueries.Row;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingReport.Assignee;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingReport.AssigneeRow;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingReport.BucketTotal;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingReport.Filters;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingReport.Money;
import com.reconflow.paymentreconflow.reporting.common.ReportParams;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the exception-ageing report (M8.3). The database does the bucketing and the money; Java
 * puts the rows into five fixed buckets (zeros included) and orders the assignees, unassigned first.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ExceptionAgeingReports {

    /** exceptions.ExceptionType's names; reporting may not depend on {@code exceptions}. */
    static final Set<String> TYPES = Set.of(
            "MISSING_IN_GATEWAY", "MISSING_IN_LEDGER", "AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "DUPLICATE_IN_SOURCE");

    private final ExceptionAgeingQueries queries;
    private final TenantContext tenant;
    private final CurrentUser currentUser;

    ExceptionAgeingReports(ExceptionAgeingQueries queries, TenantContext tenant, CurrentUser currentUser) {
        this.queries = queries;
        this.tenant = tenant;
        this.currentUser = currentUser;
    }

    /**
     * @param types repeatable; empty means every type
     * @param assignedTo a user id, {@code me} or {@code unassigned} (api-design.md §4); a user of
     *     another organization simply matches nothing
     */
    public ExceptionAgeingReport report(
            @Nullable List<String> types, @Nullable String assignedTo, @Nullable String currency) {
        List<String> typeFilter =
                types == null ? List.of() : types.stream().distinct().toList();
        for (String type : typeFilter) {
            if (!TYPES.contains(type)) {
                throw new ValidationFailedException("type", "invalid-type", "unknown exception type " + type);
            }
        }
        String code = currency == null ? null : ReportParams.currency(currency);
        Filter filter = filter(typeFilter, assignedTo, code);

        List<Row> rows = queries.ageing(tenant.organizationId(), filter);
        List<BucketTotal> totals =
                buckets(rows.stream().filter(r -> r.level() != 0).toList());

        Map<Assignee, List<Row>> byAssignee = new LinkedHashMap<>();
        List<Row> unassigned = new ArrayList<>();
        for (Row r : rows) {
            if (r.level() != 0) {
                continue;
            }
            if (r.assignedTo() == null) {
                unassigned.add(r);
            } else {
                byAssignee
                        .computeIfAbsent(
                                new Assignee(r.assignedTo(), Objects.requireNonNull(r.displayName())),
                                a -> new ArrayList<>())
                        .add(r);
            }
        }
        List<AssigneeRow> assignees = new ArrayList<>();
        if (!unassigned.isEmpty()) {
            assignees.add(row(null, unassigned));
        }
        byAssignee.entrySet().stream()
                .map(e -> row(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(AssigneeRow::cases)
                        .reversed()
                        .thenComparing(a -> Objects.requireNonNull(a.assignee()).displayName()))
                .forEach(assignees::add);

        return new ExceptionAgeingReport(queries.now(), new Filters(typeFilter, assignedTo, code), totals, assignees);
    }

    private Filter filter(List<String> types, @Nullable String assignedTo, @Nullable String currency) {
        if (assignedTo == null) {
            return new Filter(types, null, false, currency);
        }
        return switch (assignedTo) {
            case "unassigned" -> new Filter(types, null, true, currency);
            case "me" -> new Filter(types, currentUser.id(), false, currency);
            default -> {
                try {
                    yield new Filter(types, UUID.fromString(assignedTo), false, currency);
                } catch (IllegalArgumentException e) {
                    throw new ValidationFailedException(
                            "assignedTo", "invalid-assignee-filter", "assignedTo must be a user id, me or unassigned");
                }
            }
        };
    }

    private static AssigneeRow row(@Nullable Assignee assignee, List<Row> rows) {
        List<BucketTotal> buckets = buckets(rows);
        return new AssigneeRow(
                assignee, buckets.stream().mapToLong(BucketTotal::cases).sum(), buckets);
    }

    /** Five buckets in order; a row per (bucket, currency) becomes one amount per currency. */
    private static List<BucketTotal> buckets(List<Row> rows) {
        List<BucketTotal> buckets = new ArrayList<>();
        for (int i = 0; i < ExceptionAgeingReport.BUCKETS.size(); i++) {
            int bucket = i;
            List<Row> in = rows.stream().filter(r -> r.bucket() == bucket).toList();
            buckets.add(new BucketTotal(
                    ExceptionAgeingReport.BUCKETS.get(i),
                    in.stream().mapToLong(Row::cases).sum(), // counts, not money: fine to add here
                    in.stream()
                            .map(r -> new Money(r.currency(), r.amountAtStake()))
                            .sorted(Comparator.comparing(Money::currency))
                            .toList()));
        }
        return buckets;
    }
}
