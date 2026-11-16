package com.reconflow.paymentreconflow.reporting;

import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.LIST_EXCLUDES_B;
import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.NOT_FOUND;

import com.reconflow.paymentreconflow.tenancy.CrossTenantCase;
import com.reconflow.paymentreconflow.tenancy.CrossTenantCases;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * The /reports endpoints' cross-tenant cases (M3.9, M8.6). Org B has a completed run with a day
 * row and two cases, one resolved (TenantFixture): a report of org A must show none of it, and an
 * ID of org B is a 404.
 */
@Component
class ReportingCrossTenantCases implements CrossTenantCases {

    @Override
    public List<CrossTenantCase> cases() {
        return List.of(
                new CrossTenantCase(
                        "reconciliation report of org B's run",
                        HttpMethod.GET,
                        "/api/v1/reports/reconciliation?runId={id}",
                        f -> Map.of("id", f.orgBRun()),
                        null,
                        NOT_FOUND),
                new CrossTenantCase(
                        "reconciliation report over a date range",
                        HttpMethod.GET,
                        "/api/v1/reports/reconciliation?from=2026-10-01&to=2026-10-31&currency=AED",
                        f -> Map.of(),
                        null,
                        LIST_EXCLUDES_B));
    }
}
