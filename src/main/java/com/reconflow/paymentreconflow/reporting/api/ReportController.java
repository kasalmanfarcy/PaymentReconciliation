package com.reconflow.paymentreconflow.reporting.api;

import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReports;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresBothPermissions;
import java.time.LocalDate;
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

    ReportController(ReconciliationReports reconciliation) {
        this.reconciliation = reconciliation;
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
}
