package com.reconflow.paymentreconflow.exceptions;

import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.NOT_FOUND;

import com.reconflow.paymentreconflow.tenancy.CrossTenantCase;
import com.reconflow.paymentreconflow.tenancy.CrossTenantCases;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * The /exceptions endpoints' cross-tenant cases (M3.9, M7.18). Org B has an OPEN case (assigned,
 * linked to a record) and a RESOLVED one (TenantFixture); org A's admin holds every permission.
 * CrossTenantIT also checks that nothing of org B changed: case version and fields, note, history
 * and audit counts.
 */
@Component
class ExceptionCrossTenantCases implements CrossTenantCases {

    @Override
    public List<CrossTenantCase> cases() {
        List<CrossTenantCase> cases = new ArrayList<>();
        cases.add(new CrossTenantCase(
                "read org B's exception",
                HttpMethod.GET,
                "/api/v1/exceptions/{id}",
                f -> Map.of("id", f.orgBCase()),
                null,
                NOT_FOUND));
        return cases;
    }
}
