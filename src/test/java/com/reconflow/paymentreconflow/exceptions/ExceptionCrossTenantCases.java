package com.reconflow.paymentreconflow.exceptions;

import static com.reconflow.paymentreconflow.tenancy.CrossTenantCase.Expectation.LIST_EXCLUDES_B;
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

    /** The case's real current version, so the only thing that can stop the write is the tenant check. */
    static final Map<String, String> IF_MATCH_0 = Map.of("If-Match", "\"0\"");

    static final Map<String, String> VERSIONED_MERGE_PATCH =
            Map.of("If-Match", "\"0\"", "Content-Type", "application/merge-patch+json");

    @Override
    public List<CrossTenantCase> cases() {
        List<CrossTenantCase> cases = new ArrayList<>();
        cases.add(new CrossTenantCase(
                "list exceptions",
                HttpMethod.GET,
                "/api/v1/exceptions?status=OPEN&status=RESOLVED",
                f -> Map.of(),
                null,
                LIST_EXCLUDES_B));
        // Filters aimed at org B's run and user: an empty page, never a hint that they exist.
        cases.add(new CrossTenantCase(
                "list exceptions of org B's run",
                HttpMethod.GET,
                "/api/v1/exceptions?runId={runId}",
                f -> Map.of("runId", f.orgBRun()),
                null,
                LIST_EXCLUDES_B));
        cases.add(new CrossTenantCase(
                "list exceptions assigned to org B's user",
                HttpMethod.GET,
                "/api/v1/exceptions?assignedTo={userId}",
                f -> Map.of(
                        "userId",
                        f.orgBMember(com.reconflow.paymentreconflow.shared.context.Role.INVESTIGATOR)
                                .id()),
                null,
                LIST_EXCLUDES_B));
        cases.add(new CrossTenantCase(
                "read org B's exception",
                HttpMethod.GET,
                "/api/v1/exceptions/{id}",
                f -> Map.of("id", f.orgBCase()),
                null,
                NOT_FOUND));
        cases.add(new CrossTenantCase(
                "patch org B's exception",
                HttpMethod.PATCH,
                "/api/v1/exceptions/{id}",
                f -> Map.of("id", f.orgBCase()),
                f -> Map.of("status", "IN_REVIEW"),
                NOT_FOUND,
                VERSIONED_MERGE_PATCH));
        cases.add(new CrossTenantCase(
                "add a note to org B's exception",
                HttpMethod.POST,
                "/api/v1/exceptions/{id}/notes",
                f -> Map.of("id", f.orgBCase()),
                f -> Map.of("body", "planted"),
                NOT_FOUND));
        return cases;
    }
}
