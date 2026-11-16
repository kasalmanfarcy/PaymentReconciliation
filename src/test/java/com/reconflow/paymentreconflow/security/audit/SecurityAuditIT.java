package com.reconflow.paymentreconflow.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * M3.3h: each membership event is written exactly once per action, in the same transaction as the
 * change; a refused or rolled-back action leaves none; no secret is ever in a payload. Since M10.1,
 * login, lockout, logout and password-change events are Keycloak's (its login and admin events);
 * what ReconFlow still audits is linking an IdP account to a user ({@code USER_LINKED}).
 */
class SecurityAuditIT extends AbstractIntegrationTest {

    private List<Map<String, Object>> events(String action, UUID entityId) {
        return jdbc.queryForList(
                "select organization_id, actor_id, payload::text as payload from audit_event"
                        + " where action = ? and entity_id = ?",
                action,
                entityId);
    }

    private int count(String action, UUID entityId) {
        return events(action, entityId).size();
    }

    @Test
    void aFirstLoginLinkedByVerifiedEmailIsAuditedAsAPlatformEvent() throws Exception {
        var user = data().newUnlinkedUser("linked-" + UUID.randomUUID() + "@example.test");
        String subject = "kc-" + UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exceptions")
                        .header(
                                "Authorization",
                                com.reconflow.paymentreconflow.support.TestTokens.bearer(tokens().forSubject(
                                                subject,
                                                c -> c.claim("email", user.email())
                                                        .claim("email_verified", true)))))
                .andExpect(status().isNotFound()); // linked and authenticated; a member of nothing: 404

        assertThat(events("USER_LINKED", user.id())).singleElement().satisfies(e -> {
            assertThat(e.get("organization_id")).isNull();
            assertThat(e.get("actor_id")).isEqualTo(user.id());
        });
    }

    @Test
    void memberEventsCarryTheOrganizationAndTheAdminAsActor() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        var member = data().newMember(org, Role.VIEWER);

        String body = mockMvc.perform(post("/api/v1/members")
                        .with(as(admin.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "email", "audited-" + UUID.randomUUID() + "@example.test",
                                "displayName", "Audited",
                                "role", "VIEWER"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var created = jsonMapper.readTree(body);
        UUID newUser = UUID.fromString(created.get("userId").asString());
        mockMvc.perform(patch("/api/v1/members/" + member.id())
                        .with(as(admin.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"INVESTIGATOR\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/members/" + member.id() + "/reset-password")
                        .with(as(admin.id())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/members/" + member.id() + "/disable").with(as(admin.id())))
                .andExpect(status().isOk());

        assertThat(events("MEMBER_CREATED", newUser)).singleElement().satisfies(e -> {
            assertThat(e.get("organization_id")).isEqualTo(org);
            assertThat(e.get("actor_id")).isEqualTo(admin.id());
            assertThat((String) e.get("payload"))
                    .doesNotContain(created.get("temporaryPassword").asString());
        });
        assertThat(events("MEMBER_ROLE_CHANGED", member.id()))
                .singleElement()
                .satisfies(e -> assertThat((String) e.get("payload")).contains("VIEWER", "INVESTIGATOR"));
        assertThat(count("PASSWORD_RESET_BY_ADMIN", member.id())).isEqualTo(1);
        assertThat(count("MEMBER_DISABLED", member.id())).isEqualTo(1);
    }

    @Test
    void aRefusedChangeLeavesNoEvent() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        var member = data().newMember(org, Role.VIEWER);

        // 409 already-a-member: the transaction rolls back, and so would any audit row.
        mockMvc.perform(post("/api/v1/members")
                        .with(as(admin.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("email", member.email(), "displayName", "Again", "role", "VIEWER"))))
                .andExpect(status().isConflict());
        // 409 last-org-admin.
        mockMvc.perform(patch("/api/v1/members/" + admin.id())
                        .with(as(admin.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict());

        assertThat(count("MEMBER_CREATED", member.id())).isZero();
        assertThat(count("MEMBER_ROLE_CHANGED", admin.id())).isZero();
    }

    @Test
    void creatingAnOrganizationIsRecordedInThatOrganizationsLog() throws Exception {
        var platformAdmin = data().newPlatformAdmin();

        String body = mockMvc.perform(post("/api/v1/platform/organizations")
                        .with(as(platformAdmin.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "name", "Audited Org",
                                "adminEmail", "first-" + UUID.randomUUID() + "@example.test",
                                "adminDisplayName", "First"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = jsonMapper.readTree(body);
        UUID orgId = UUID.fromString(json.get("organization").get("id").asString());
        UUID adminId = UUID.fromString(json.get("admin").get("userId").asString());

        assertThat(events("ORGANIZATION_CREATED", orgId)).singleElement().satisfies(e -> {
            assertThat(e.get("organization_id")).isEqualTo(orgId);
            assertThat(e.get("actor_id")).isEqualTo(platformAdmin.id());
        });
        assertThat(events("MEMBER_CREATED", adminId))
                .singleElement()
                .satisfies(e -> assertThat(e.get("organization_id")).isEqualTo(orgId));
    }
}
