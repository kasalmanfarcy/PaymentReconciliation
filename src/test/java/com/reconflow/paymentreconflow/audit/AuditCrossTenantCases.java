package com.reconflow.paymentreconflow.audit;

import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.LIST_EXCLUDES_B;

import com.reconflow.paymentreconflow.tenancy.CrossTenantCase;
import com.reconflow.paymentreconflow.tenancy.CrossTenantCases;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * GET /audit-events (M7.13, M7.18): org B's case's timeline, asked for by org A's admin, is an
 * empty page, never a 404 or 403 that would confirm the id.
 */
@Component
class AuditCrossTenantCases implements CrossTenantCases {

    @Override
    public List<CrossTenantCase> cases() {
        return List.of(new CrossTenantCase(
                "audit timeline of org B's exception",
                HttpMethod.GET,
                "/api/v1/audit-events?entityType=EXCEPTION_CASE&entityId={id}",
                f -> Map.of("id", f.orgBCase()),
                null,
                LIST_EXCLUDES_B));
    }
}
