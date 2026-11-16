package com.reconflow.paymentreconflow.reporting.api;

import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingReport;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingReports;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReports;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReports;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresBothPermissions;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/reports} (api-design.md §8.21–§8.25): read-only aggregate reports and the
 * exception export. The organization always comes from TenantContext, never from a parameter.
 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    private final ReconciliationReports reconciliation;
    private final ImportQualityReports importQuality;
    private final ExceptionAgeingReports ageing;

    ReportController(
            ReconciliationReports reconciliation, ImportQualityReports importQuality, ExceptionAgeingReports ageing) {
        this.reconciliation = reconciliation;
        this.importQuality = importQuality;
        this.ageing = ageing;
    }

    /**
     * {@code ?runId=} or {@code ?from=&to=&currency=}, exactly one of the two. Needs
     * {@code EXCEPTION_READ} too: it shows the cases and who resolved them.
     */
    @GetMapping("/reconciliation")
    @RequiresBothPermissions(value = Permission.REPORT_READ, and = Permission.EXCEPTION_READ)
    ReconciliationReport reconciliation(
            @RequestParam(required = false) @Nullable UUID runId,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(required = false) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable String currency) {
        return reconciliation.report(runId, from, to, currency);
    }

    /**
     * {@code ?batchId=}, or {@code ?from=&to=} (UTC days) with optional {@code granularity}
     * ({@code day} by default, {@code week}, {@code month}) and {@code sourceType}.
     */
    @GetMapping("/import-quality")
    @RequiresPermission(Permission.REPORT_READ)
    ImportQualityReport importQuality(
            @RequestParam(required = false) @Nullable UUID batchId,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(required = false) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable String granularity,
            @RequestParam(required = false) @Nullable String sourceType) {
        return importQuality.report(batchId, from, to, granularity, sourceType);
    }

    /**
     * Open and in-review cases by age bucket and assignee. Filters: {@code type} (repeatable),
     * {@code assignedTo} (a user id, {@code me} or {@code unassigned}), {@code currency}.
     */
    @GetMapping("/exception-ageing")
    @RequiresPermission(Permission.REPORT_READ)
    ExceptionAgeingReport exceptionAgeing(
            @RequestParam(required = false) @Nullable List<String> type,
            @RequestParam(required = false) @Nullable String assignedTo,
            @RequestParam(required = false) @Nullable String currency) {
        return ageing.report(type, assignedTo, currency);
    }
}
