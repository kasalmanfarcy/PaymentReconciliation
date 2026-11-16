package com.reconflow.paymentreconflow.reconciliation;

import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.AFFECTS_ONLY_A;
import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.NOT_FOUND;

import com.reconflow.paymentreconflow.tenancy.CrossTenantCase;
import com.reconflow.paymentreconflow.tenancy.CrossTenantCases;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * The /reconciliation-runs endpoints' cross-tenant cases (M3.9, M6.21). Org B has a completed run
 * with a match and a failed run (TenantFixture).
 */
@Component
class ReconciliationCrossTenantCases implements CrossTenantCases {

    @Override
    public List<CrossTenantCase> cases() {
        return List.of(
                // A run always lands in the caller's organization and only ever reads its records;
                // org B's identical scope doesn't make A's request a repeat of B's run.
                new CrossTenantCase(
                        "request a run",
                        HttpMethod.POST,
                        "/api/v1/reconciliation-runs",
                        f -> Map.of(),
                        f -> Map.of("periodStart", "2026-10-01", "periodEnd", "2026-10-31", "currency", "AED"),
                        AFFECTS_ONLY_A),
                new CrossTenantCase(
                        "read org B's run",
                        HttpMethod.GET,
                        "/api/v1/reconciliation-runs/{id}",
                        f -> Map.of("id", f.orgBRun()),
                        null,
                        NOT_FOUND),
                new CrossTenantCase(
                        "read org B's run matches",
                        HttpMethod.GET,
                        "/api/v1/reconciliation-runs/{id}/matches",
                        f -> Map.of("id", f.orgBRun()),
                        null,
                        NOT_FOUND),
                // Org B's failed run must stay FAILED, its job count unchanged (the snapshot).
                new CrossTenantCase(
                        "retry org B's failed run",
                        HttpMethod.POST,
                        "/api/v1/reconciliation-runs/{id}/retry",
                        f -> Map.of("id", f.orgBFailedRun()),
                        f -> Map.of("reason", "hijack"),
                        NOT_FOUND));
    }
}
