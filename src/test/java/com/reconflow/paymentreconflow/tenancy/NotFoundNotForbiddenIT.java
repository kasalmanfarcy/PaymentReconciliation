package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * M3.6: another organization's ID answers exactly like an ID that never existed. A 403 would
 * confirm the ID is real, letting a caller enumerate IDs or probe for a specific tenant; the 404
 * body may mention only the ID the caller sent, so after swapping that ID out the two responses
 * must be byte-identical.
 */
class NotFoundNotForbiddenIT extends AbstractIntegrationTest {

    interface Call {
        MockHttpServletRequestBuilder at(UUID id);
    }

    static final List<Call> MEMBER_CALLS = List.of(
            id -> patch("/api/v1/members/" + id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"role\":\"VIEWER\"}"),
            id -> post("/api/v1/members/" + id + "/reset-password"),
            id -> post("/api/v1/members/" + id + "/disable"));

    @Test
    void anotherTenantsMemberIsIndistinguishableFromAnUnknownOne() throws Exception {
        UUID orgA = data().newOrganization();
        var adminA = data().newMember(orgA, Role.ORG_ADMIN);
        var memberOfB = data().newMember(data().newOrganization(), Role.VIEWER);
        UUID neverExisted = UUID.randomUUID();

        for (Call call : MEMBER_CALLS) {
            MockHttpServletResponse foreign = mockMvc.perform(
                            call.at(memberOfB.id()).with(as(adminA.id())))
                    .andReturn()
                    .getResponse();
            MockHttpServletResponse unknown = mockMvc.perform(
                            call.at(neverExisted).with(as(adminA.id())))
                    .andReturn()
                    .getResponse();

            assertThat(foreign.getStatus()).isEqualTo(unknown.getStatus()).isEqualTo(404);
            assertThat(normalize(foreign.getContentAsString(), memberOfB.id()))
                    .isEqualTo(normalize(unknown.getContentAsString(), neverExisted))
                    .contains("\"code\":\"not-found\"");
        }
        // And the foreign member was not touched.
        assertThat(jdbc.queryForObject("select status from app_user where id = ?", String.class, memberOfB.id()))
                .isEqualTo("ACTIVE");
    }

    /** The requested ID and the per-request requestId are the only permitted differences. */
    private static String normalize(String body, UUID requested) {
        return body.replace(requested.toString(), "{id}").replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"-\"");
    }
}
