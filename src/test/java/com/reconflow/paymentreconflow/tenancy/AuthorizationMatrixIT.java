package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.security.authz.RolePermissions;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * M3.7: endpoint × role, each expectation taken from the matrix (api-design.md §9) and the
 * permission listed here independently of the annotation: a role with the permission gets 2xx,
 * every other role a 403 {@code forbidden} (it is a member, so not a 404).
 */
class AuthorizationMatrixIT extends AbstractIntegrationTest {

    record Endpoint(String name, Permission required, Function<UUID, MockHttpServletRequestBuilder> request) {
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
                target -> patch("/api/v1/members/" + target)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"UPLOADER\"}")),
        new Endpoint(
                "POST /members/{userId}/reset-password",
                Permission.MEMBER_MANAGE,
                target -> post("/api/v1/members/" + target + "/reset-password")),
        new Endpoint(
                "POST /members/{userId}/disable",
                Permission.MEMBER_MANAGE,
                target -> post("/api/v1/members/" + target + "/disable")),
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

        MockHttpServletResponse response = mockMvc.perform(
                        endpoint.request().apply(target.id()).with(as(caller.id())))
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
