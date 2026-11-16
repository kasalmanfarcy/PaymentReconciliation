package com.reconflow.paymentreconflow.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.security.token.RefreshCookies;
import com.reconflow.paymentreconflow.security.token.TokenHasher;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestData;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M3.3h: each authentication and membership event is written exactly once per action, in the same
 * transaction as the change; a refused or rolled-back action leaves none; no secret is ever in a
 * payload.
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

    private static String cookie(MvcResult result) {
        return result.getResponse().getCookie(RefreshCookies.NAME).getValue();
    }

    @Test
    void successfulAndFailedLoginsAreRecordedOnceWithoutSecrets() throws Exception {
        var user = data().newUser();

        login(user.email(), TestData.PASSWORD).andExpect(status().isOk());
        login(user.email(), "a-wrong-password-guess").andExpect(status().isUnauthorized());

        var succeeded = events("LOGIN_SUCCEEDED", user.id());
        assertThat(succeeded).singleElement().satisfies(e -> {
            assertThat(e.get("actor_id")).isEqualTo(user.id());
            assertThat(e.get("organization_id")).isNull(); // login is global
            assertThat((String) e.get("payload")).contains("\"ip\"");
        });
        var failed = events("LOGIN_FAILED", user.id());
        assertThat(failed)
                .singleElement()
                .satisfies(e -> assertThat((String) e.get("payload"))
                        .contains("bad-password")
                        .doesNotContain("a-wrong-password-guess")
                        .doesNotContain(TestData.PASSWORD));
    }

    @Test
    void anUnknownEmailIsRecordedAsAHashNotAsTyped() throws Exception {
        String email = "Stuffing-" + UUID.randomUUID() + "@Example.test";
        String hash = TokenHasher.sha256Hex(email.toLowerCase());

        login(email, "some-password-guess").andExpect(status().isUnauthorized());

        var rows = jdbc.queryForList(
                "select actor_id, payload::text as payload from audit_event"
                        + " where action = 'LOGIN_FAILED' and payload->>'emailSha256' = ?",
                hash);
        assertThat(rows).singleElement().satisfies(e -> {
            assertThat(e.get("actor_id")).isNull();
            assertThat((String) e.get("payload")).doesNotContain(email).doesNotContain(email.toLowerCase());
        });
    }

    @Test
    void theLockIsRecordedExactlyOnce() throws Exception {
        var user = data().newUser();
        for (int i = 0; i < 7; i++) {
            login(user.email(), "wrong-guess-" + i);
        }

        assertThat(count("ACCOUNT_LOCKED", user.id())).isEqualTo(1);
        assertThat(count("LOGIN_FAILED", user.id())).isEqualTo(7);
    }

    @Test
    void reuseIsRecordedOnceButAnOrdinaryRevokedTokenRaisesNothing() throws Exception {
        var user = data().newUser();
        String first = cookie(login(user.email(), TestData.PASSWORD).andReturn());
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(RefreshCookies.NAME, first)))
                .andExpect(status().isOk());
        UUID family = jdbc.queryForObject(
                "select family_id from refresh_token where token_hash = ?", UUID.class, TokenHasher.sha256Hex(first));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(RefreshCookies.NAME, first)))
                .andExpect(status().isUnauthorized());

        assertThat(events("REFRESH_TOKEN_REUSE", family))
                .singleElement()
                .satisfies(e -> assertThat(e.get("actor_id")).isEqualTo(user.id()));

        // A token revoked by logout comes back from another device: 401, and no alarm.
        String other = cookie(login(user.email(), TestData.PASSWORD).andReturn());
        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(RefreshCookies.NAME, other)));
        UUID otherFamily = jdbc.queryForObject(
                "select family_id from refresh_token where token_hash = ?", UUID.class, TokenHasher.sha256Hex(other));
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(RefreshCookies.NAME, other)))
                .andExpect(status().isUnauthorized());
        assertThat(count("REFRESH_TOKEN_REUSE", otherFamily)).isZero();
    }

    @Test
    void logoutAndPasswordChangeAreRecordedOnce() throws Exception {
        var user = data().newUser();
        MvcResult session = login(user.email(), TestData.PASSWORD).andReturn();
        String token = jsonMapper
                .readTree(session.getResponse().getContentAsString())
                .get("accessToken")
                .asString();

        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "currentPassword", TestData.PASSWORD, "newPassword", "another-long-passphrase"))))
                .andExpect(status().isOk());
        String newCookie = cookie(login(user.email(), "another-long-passphrase").andReturn());
        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(RefreshCookies.NAME, newCookie)))
                .andExpect(status().isNoContent());
        // Logging out twice is still one LOGOUT: the second finds nothing live to revoke.
        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(RefreshCookies.NAME, newCookie)));

        assertThat(events("PASSWORD_CHANGED", user.id()))
                .singleElement()
                .satisfies(e -> assertThat((String) e.get("payload")).doesNotContain("another-long-passphrase"));
        assertThat(count("LOGOUT", user.id())).isEqualTo(1);
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
