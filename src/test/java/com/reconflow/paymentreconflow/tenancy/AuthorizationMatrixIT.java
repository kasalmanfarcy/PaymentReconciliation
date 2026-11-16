package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.security.authz.RolePermissions;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * M3.7: endpoint × role, each expectation taken from the matrix (api-design.md §9) and the
 * permission listed here independently of the annotation: a role with the permission gets 2xx,
 * every other role a 403 {@code forbidden} (it is a member, so not a 404).
 */
class AuthorizationMatrixIT extends AbstractIntegrationTest {

    /**
     * What a request may aim at, all in the caller's organization: a member, a FAILED import and run,
     * an OPEN exception case and a RESOLVED one (both at version 0).
     */
    record Target(UUID userId, UUID batchId, UUID runId, UUID openCaseId, UUID resolvedCaseId) {}

    /** {@code required}: every permission the endpoint needs (M8's reports need two). */
    record Endpoint(
            String name, Set<Permission> required, Function<Target, AbstractMockHttpServletRequestBuilder<?>> request) {

        Endpoint(String name, Permission required, Function<Target, AbstractMockHttpServletRequestBuilder<?>> request) {
            this(name, Set.of(required), request);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static final Endpoint[] ENDPOINTS = {
        new Endpoint("GET /members", Permission.MEMBER_MANAGE, target -> get("/api/v1/members")),
        new Endpoint(
                "POST /members",
                Permission.MEMBER_MANAGE,
                target -> post("/api/v1/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"m-" + UUID.randomUUID()
                                + "@example.test\",\"displayName\":\"M\",\"role\":\"VIEWER\"}")),
        new Endpoint(
                "PATCH /members/{userId}",
                Permission.MEMBER_MANAGE,
                target -> patch("/api/v1/members/" + target.userId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"UPLOADER\"}")),
        new Endpoint(
                "POST /members/{userId}/reset-password",
                Permission.MEMBER_MANAGE,
                target -> post("/api/v1/members/" + target.userId() + "/reset-password")),
        new Endpoint(
                "POST /members/{userId}/disable",
                Permission.MEMBER_MANAGE,
                target -> post("/api/v1/members/" + target.userId() + "/disable")),
        new Endpoint(
                "POST /imports",
                Permission.IMPORT_CREATE,
                target -> multipart("/api/v1/imports")
                        .file(new MockMultipartFile(
                                "file",
                                "ledger.csv",
                                "text/csv",
                                ("reference,amount,currency,status,transaction_date\nPAY-" + UUID.randomUUID()
                                                + ",1.00,AED,SUCCESS,2026-10-01\n")
                                        .getBytes(StandardCharsets.UTF_8)))
                        .param("sourceType", "INTERNAL_LEDGER")),
        new Endpoint("GET /imports", Permission.IMPORT_READ, target -> get("/api/v1/imports")),
        new Endpoint(
                "POST /imports/{id}/retry",
                Permission.IMPORT_CREATE,
                target -> post("/api/v1/imports/" + target.batchId() + "/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"storage outage\"}")),
        new Endpoint(
                "GET /transactions",
                Permission.TRANSACTION_READ,
                target -> get("/api/v1/transactions?transactionDateFrom=2026-10-01&transactionDateTo=2026-10-31")),
        new Endpoint("GET /imports/{id}", Permission.IMPORT_READ, target -> get("/api/v1/imports/" + target.batchId())),
        new Endpoint(
                "POST /reconciliation-runs",
                Permission.RUN_CREATE,
                target -> post("/api/v1/reconciliation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2026-10-31\",\"currency\":\"AED\"}")),
        new Endpoint("GET /reconciliation-runs", Permission.RUN_READ, target -> get("/api/v1/reconciliation-runs")),
        new Endpoint(
                "GET /reconciliation-runs/{id}",
                Permission.RUN_READ,
                target -> get("/api/v1/reconciliation-runs/" + target.runId())),
        new Endpoint(
                "GET /reconciliation-runs/{id}/matches",
                Permission.RUN_READ,
                target -> get("/api/v1/reconciliation-runs/" + target.runId() + "/matches")),
        new Endpoint(
                "POST /reconciliation-runs/{id}/retry",
                Permission.RUN_CREATE,
                target -> post("/api/v1/reconciliation-runs/" + target.runId() + "/retry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"database restarted\"}")),
        new Endpoint("GET /exceptions", Permission.EXCEPTION_READ, target -> get("/api/v1/exceptions")),
        new Endpoint(
                "GET /exceptions/{id}",
                Permission.EXCEPTION_READ,
                target -> get("/api/v1/exceptions/" + target.openCaseId())),
        new Endpoint(
                "PATCH /exceptions/{id}",
                Permission.EXCEPTION_UPDATE,
                target -> patch("/api/v1/exceptions/" + target.openCaseId())
                        .contentType("application/merge-patch+json")
                        .header("If-Match", "\"0\"")
                        .content("{\"status\":\"IN_REVIEW\"}")),
        new Endpoint(
                "POST /exceptions/{id}/notes",
                Permission.EXCEPTION_UPDATE,
                target -> post("/api/v1/exceptions/" + target.openCaseId() + "/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"checked with the gateway\"}")),
        new Endpoint(
                "POST /exceptions/{id}/resolve",
                Permission.EXCEPTION_RESOLVE,
                target -> post("/api/v1/exceptions/" + target.openCaseId() + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"0\"")
                        .content("{\"resolutionCode\":\"GATEWAY_FEE\"}")),
        new Endpoint(
                "POST /exceptions/{id}/reopen",
                Permission.EXCEPTION_REOPEN,
                target -> post("/api/v1/exceptions/" + target.resolvedCaseId() + "/reopen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"0\"")
                        .content("{\"reason\":\"the fee was wrong\"}")),
        new Endpoint(
                "GET /audit-events",
                Permission.AUDIT_READ,
                target -> get("/api/v1/audit-events?entityType=EXCEPTION_CASE&entityId=" + target.openCaseId())),
        new Endpoint(
                "GET /imports/{id}/errors",
                Permission.IMPORT_READ,
                target -> get("/api/v1/imports/" + target.batchId() + "/errors")),
        // M8: the date-range mode (the target run is FAILED, so run mode would be a 409).
        new Endpoint(
                "GET /reports/reconciliation",
                Set.of(Permission.REPORT_READ, Permission.EXCEPTION_READ),
                target -> get("/api/v1/reports/reconciliation?from=2026-10-01&to=2026-10-31&currency=AED")),
    };

    static Stream<Arguments> endpointByRole() {
        return Stream.of(ENDPOINTS).flatMap(e -> Stream.of(Role.values()).map(r -> Arguments.of(e, r)));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("endpointByRole")
    void theMatrixDecides(Endpoint endpoint, Role role) throws Exception {
        UUID org = data().newOrganization();
        data().newMember(org, Role.ORG_ADMIN); // so demoting or disabling the caller is never "last admin"
        var caller = data().newMember(org, role);
        var target = data().newMember(org, Role.VIEWER);
        UUID batch = data().newImportBatch(org, target.id(), "GATEWAY", "FAILED");
        UUID run = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, status, triggered_by, failure_reason)
                VALUES (?, ?, ?, ?, DATE '2026-09-01', DATE '2026-09-30', 'AED', 'FAILED', ?, 'internal-error')""", run, org, "client:" + run, "0".repeat(64), target.id());

        UUID openCase = newCase(org, run, "OPEN", target.id());
        UUID resolvedCase = newCase(org, run, "RESOLVED", target.id());

        MockHttpServletResponse response = mockMvc.perform(endpoint.request()
                        .apply(new Target(target.id(), batch, run, openCase, resolvedCase))
                        .with(as(caller.id())))
                .andReturn()
                .getResponse();

        if (RolePermissions.of(role).containsAll(endpoint.required())) {
            assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(200, 299);
        } else {
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("\"code\":\"forbidden\"");
        }
    }

    /** A case as DiscrepancyRecorder would have created it (and, if RESOLVED, someone resolved it). */
    private UUID newCase(UUID org, UUID run, String status, UUID resolver) {
        UUID id = UUID.randomUUID();
        boolean resolved = status.equals("RESOLVED");
        jdbc.update(
                """
                INSERT INTO exception_case (id, organization_id, run_id, type, discrepancy_key, status, currency, amount,
                       resolution_code, resolved_by, resolved_at)
                VALUES (?, ?, ?, 'MISSING_IN_LEDGER', ?, ?, 'AED', 10, ?, ?, CASE WHEN ? THEN now() END)""",
                id,
                org,
                run,
                "MISSING_IN_LEDGER:PAY-" + id + ":PAYMENT:AED",
                status,
                resolved ? "GATEWAY_FEE" : null,
                resolved ? resolver : null,
                resolved);
        return id;
    }
}
