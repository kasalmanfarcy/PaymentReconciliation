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

    /** What a request may aim at, all in the caller's organization: a member and a FAILED import. */
    record Target(UUID userId, UUID batchId) {}

    record Endpoint(
            String name, Permission required, Function<Target, AbstractMockHttpServletRequestBuilder<?>> request) {
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
        new Endpoint("GET /imports/{id}", Permission.IMPORT_READ, target -> get("/api/v1/imports/" + target.batchId())),
        new Endpoint(
                "GET /imports/{id}/errors",
                Permission.IMPORT_READ,
                target -> get("/api/v1/imports/" + target.batchId() + "/errors")),
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

        MockHttpServletResponse response = mockMvc.perform(
                        endpoint.request().apply(new Target(target.id(), batch)).with(as(caller.id())))
                .andReturn()
                .getResponse();

        if (RolePermissions.of(role).contains(endpoint.required())) {
            assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(200, 299);
        } else {
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("\"code\":\"forbidden\"");
        }
    }
}
