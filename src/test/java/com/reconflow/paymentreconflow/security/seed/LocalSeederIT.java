package com.reconflow.paymentreconflow.security.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.organization.OrganizationService;
import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import com.reconflow.paymentreconflow.security.user.MembershipRepository;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The seeder is off in the test profile (tests make their own fixtures), so it is built by hand.
 * It uses fixed IDs and emails, so this is the only test that may run it.
 */
class LocalSeederIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    @Autowired
    AppUserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    TenantScope tenantScope;

    @Autowired
    TransactionTemplate tx;

    @Test
    void seedsOnceAndTheSeededUsersAreTheRealmsAccounts() throws Exception {
        var seeder = new LocalSeeder(organizations, users, memberships, tenantScope, tx);

        assertThat(seeder.seed()).isTrue();
        assertThat(seeder.seed()).isFalse(); // a second start seeds nothing

        assertThat(jdbc.queryForObject(
                        "select count(*) from membership where organization_id in (?, ?)",
                        Integer.class,
                        LocalSeeder.ACME_ID,
                        LocalSeeder.GLOBEX_ID))
                .isEqualTo(10); // 4 roles x 2 orgs + the consultant in both
        assertThat(jdbc.queryForObject(
                        "select is_platform_admin from app_user where email = 'platform@reconflow.test'",
                        Boolean.class))
                .isTrue();
        // A token for the realm's admin@acme.test (its fixed sub) is that seeded user, an org admin.
        mockMvc.perform(get("/api/v1/members")
                        .header("Authorization", bearer(LocalSeeder.SUBJECTS.get("admin@acme.test")))
                        .header("X-Organization-Id", LocalSeeder.ACME_ID.toString()))
                .andExpect(status().isOk());
        // The consultant is in both organizations, so they must pick one.
        mockMvc.perform(get("/api/v1/exceptions")
                        .header("Authorization", bearer(LocalSeeder.SUBJECTS.get("consultant@both.test"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("organization-required"));
        // Each organization's creation is audited in that organization's own log.
        assertThat(jdbc.queryForObject(
                        "select organization_id from audit_event where action = 'ORGANIZATION_CREATED' and entity_id = ?",
                        java.util.UUID.class,
                        LocalSeeder.GLOBEX_ID))
                .isEqualTo(LocalSeeder.GLOBEX_ID);
    }

    private String bearer(String subject) {
        return TestTokens.bearer(tokens().forSubject(subject, c -> {}));
    }

    /**
     * The seeder and the local Keycloak realm describe the same people: every seeded user exists in
     * the realm with the same id and a verified email, and every realm user is seeded (apart from the
     * service account). If the two drift, local logins get 401 for no visible reason.
     */
    @Test
    void theSeedMatchesTheLocalKeycloakRealm() throws Exception {
        JsonNode realm = jsonMapper.readTree(Files.readString(Path.of("infra/keycloak/realm-reconflow.json")));
        Map<String, String> realmUsers = new HashMap<>();
        for (JsonNode user : realm.get("users")) {
            if (user.has("serviceAccountClientId")) {
                continue;
            }
            assertThat(user.get("emailVerified").asBoolean())
                    .as(user.get("email").asString())
                    .isTrue();
            assertThat(user.get("username").asString())
                    .isEqualTo(user.get("email").asString());
            realmUsers.put(user.get("email").asString(), user.get("id").asString());
        }
        assertThat(realmUsers).isEqualTo(LocalSeeder.SUBJECTS);
    }
}
