package com.reconflow.paymentreconflow.reporting.reconciliation;

import com.reconflow.paymentreconflow.reporting.common.ReportParams;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.Count;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.CountAndAmount;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.Coverage;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.MismatchSummary;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.Mode;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.ReasonCount;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.ResolutionSummary;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.ResolverCount;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.RunRef;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport.Scope;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReportQueries.DayRun;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReportQueries.RunRow;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReportQueries.Totals;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.ConflictException;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the reconciliation report (M8.1) from a handful of aggregate queries. Java only shapes the
 * result: it adds up <em>counts</em> of the per-reason rows into the status totals, and never adds
 * money.
 *
 * <p>REPEATABLE READ, read-only: the run row, the reason breakdown and the resolvers come from one
 * snapshot, so a case resolved between two statements can't make the sections disagree. Read-only
 * also makes an accidental write fail, and the organization is bound for RLS at transaction start,
 * as for any transaction.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReconciliationReports {

    private final ReconciliationReportQueries queries;
    private final TenantContext tenant;

    ReconciliationReports(ReconciliationReportQueries queries, TenantContext tenant) {
        this.queries = queries;
        this.tenant = tenant;
    }

    /**
     * Picks the mode: exactly one of {@code runId} or {@code from}/{@code to}/{@code currency}.
     */
    public ReconciliationReport report(
            @Nullable UUID runId, @Nullable LocalDate from, @Nullable LocalDate to, @Nullable String currency) {
        boolean rangeGiven = from != null || to != null || currency != null;
        if (runId != null && rangeGiven) {
            throw new ValidationFailedException(
                    "runId", "conflicting-parameters", "send either runId or from, to and currency, not both");
        }
        if (runId != null) {
            return forRun(runId);
        }
        if (!rangeGiven) {
            throw new ValidationFailedException("runId", "required", "send either runId or from, to and currency");
        }
        LocalDate start = ReportParams.required(from, "from");
        LocalDate end = ReportParams.required(to, "to");
        String code = ReportParams.currency(ReportParams.required(currency, "currency"));
        ReportParams.range(start, end);
        return forRange(start, end, code);
    }

    /**
     * One run: its precomputed totals (written once, at completion) plus its cases, computed on
     * read because their statuses keep changing after the run.
     *
     * @throws NotFoundException unknown run, or another organization's (ADR 0008)
     * @throws ConflictException {@code run-not-completed}: totals exist only once a run completes
     */
    public ReconciliationReport forRun(UUID runId) {
        UUID orgId = tenant.organizationId();
        RunRow run = queries.run(orgId, runId).orElseThrow(() -> new NotFoundException("Run", runId));
        if (!run.status().equals("COMPLETED")) {
            throw new ConflictException(
                    ProblemCodes.RUN_NOT_COMPLETED,
                    "The report is available once the run is COMPLETED; it is " + run.status() + ".",
                    Map.of("currentStatus", run.status()));
        }
        List<ReasonCount> reasons = queries.reasonsForRun(orgId, runId);
        List<ResolverCount> resolvers = queries.resolversForRun(orgId, runId);
        List<LocalDate> days =
                run.periodStart().datesUntil(run.periodEnd().plusDays(1)).toList();
        return build(
                new Scope(Mode.RUN, runId, run.periodStart(), run.periodEnd()),
                run.currency(),
                run.totals(),
                new Count(run.excludedCount()),
                reasons,
                resolvers,
                List.of(new RunRef(runId, run.completedAt(), days)),
                new Coverage(days.size(), days.size(), List.of()));
    }

    /**
     * A date range: per day, the latest completed run's results only, so overlapping runs never
     * count a day twice; days no completed run covered are listed as missing.
     */
    public ReconciliationReport forRange(LocalDate from, LocalDate to, String currency) {
        UUID orgId = tenant.organizationId();
        Totals totals = queries.rangeTotals(orgId, from, to, currency);
        List<DayRun> dayRuns = queries.rangeDays(orgId, from, to, currency);
        List<ReasonCount> reasons = queries.rangeReasons(orgId, from, to, currency);
        List<ResolverCount> resolvers = queries.rangeResolvers(orgId, from, to, currency);

        Map<UUID, RunRef> runs = new LinkedHashMap<>();
        Set<LocalDate> covered = new TreeSet<>();
        for (DayRun d : dayRuns) {
            covered.add(d.day());
            runs.computeIfAbsent(d.runId(), id -> new RunRef(id, d.completedAt(), new ArrayList<>()))
                    .days()
                    .add(d.day());
        }
        List<LocalDate> missing = from.datesUntil(to.plusDays(1))
                .filter(day -> !covered.contains(day))
                .toList();
        List<RunRef> runsUsed = runs.values().stream()
                .map(r -> new RunRef(r.runId(), r.completedAt(), List.copyOf(r.days())))
                .collect(Collectors.toList());
        return build(
                new Scope(Mode.DATE_RANGE, null, from, to),
                currency,
                totals,
                null,
                reasons,
                resolvers,
                runsUsed,
                new Coverage(ReportParams.days(from, to), covered.size(), missing));
    }

    private static ReconciliationReport build(
            Scope scope,
            String currency,
            Totals t,
            @Nullable Count excluded,
            List<ReasonCount> reasons,
            List<ResolverCount> resolvers,
            List<RunRef> runsUsed,
            Coverage coverage) {
        long open = reasons.stream().mapToLong(ReasonCount::open).sum();
        long inReview = reasons.stream().mapToLong(ReasonCount::inReview).sum();
        long resolved = reasons.stream().mapToLong(ReasonCount::resolved).sum();
        return new ReconciliationReport(
                scope,
                currency,
                new CountAndAmount(t.matchedCount(), t.matchedAmount()),
                new CountAndAmount(t.unmatchedLedgerCount(), t.unmatchedLedgerAmount()),
                new CountAndAmount(t.unmatchedGatewayCount(), t.unmatchedGatewayAmount()),
                new MismatchSummary(t.mismatchCount(), t.mismatchNetDifference()),
                new Count(t.duplicateCount()),
                new Count(t.currencyMismatchCount()),
                excluded,
                reasons,
                new ResolutionSummary(open, inReview, resolved, resolvers),
                runsUsed,
                coverage);
    }
}
