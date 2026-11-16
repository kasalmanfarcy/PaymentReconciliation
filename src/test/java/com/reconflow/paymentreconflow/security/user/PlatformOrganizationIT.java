package com.reconflow.paymentreconflow.security.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** M3.3g: platform admins create organizations with their first admin; the two worlds stay apart. */
class PlatformOrganizationIT extends AbstractIntegrationTest {

    private ResultActions create(UUID caller, String name, String adminEmail) throws Exception {
        return mockMvc.perform(post("/api/v1/platform/organizations")
                .with(as(caller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(
                        Map.of("name", name, "adminEmail", adminEmail, "adminDisplayName", "First Admin"))));
    }

    @Test
    void aPlatformAdminCreatesAnOrganizationAndItsFirstAdmin() throws Exception {
        var platformAdmin = data().newPlatformAdmin();
        String adminEmail = "first-admin-" + UUID.randomUUID() + "@example.test";

        String body = create(platformAdmin.id(), "Initech", adminEmail)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.organization.name").value("Initech"))
                .andExpect(jsonPath("$.organization.status").value("ACTIVE"))
                .andExpect(jsonPath("$.organization.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.admin.email").value(adminEmail))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var json = jsonMapper.readTree(body);
        UUID orgId = UUID.fromString(json.get("organization").get("id").asString());
        String temporaryPassword = json.get("admin").get("temporaryPassword").asString();

        assertThat(jdbc.queryForObject(
                        "select m.role from membership m join app_user u on u.id = m.user_id"
                                + " where m.organization_id = ? and u.email = ?",
                        String.class,
                        orgId,
                        adminEmail))
                .isEqualTo("ORG_ADMIN");
        // The first admin's Keycloak account exists with that temporary password, linked by sub; a
        // token for it is the new organization's admin.
        String subject = jdbc.queryForObject(
                "select external_subject from app_user where lower(email) = lower(?)", String.class, adminEmail);
        assertThat(idp.account(subject).password()).isEqualTo(temporaryPassword);
        mockMvc.perform(get("/api/v1/members")
                        .header(
                                "Authorization",
                                com.reconflow.paymentreconflow.support.TestTokens.bearer(
                                        tokens().forSubject(subject, c -> {}))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/platform/organizations")
                        .with(as(platformAdmin.id()))
                        .param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content[?(@.id == '" + orgId + "')].name").value("Initech"));
    }

    @Test
    void anOrgAdminGets403OnEveryPlatformRoute() throws Exception {
        var orgAdmin = data().newMember(data().newOrganization(), Role.ORG_ADMIN);

        mockMvc.perform(get("/api/v1/platform/organizations").with(as(orgAdmin.id())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        create(orgAdmin.id(), "Sneaky", "sneaky-" + UUID.randomUUID() + "@example.test")
                .andExpect(status().isForbidden());
    }

    @Test
    void aPlatformAdminGets404OnOrgRoutes() throws Exception {
        var platformAdmin = data().newPlatformAdmin();
        UUID someOrg = data().newOrganization();

        mockMvc.perform(get("/api/v1/members").with(as(platformAdmin.id())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("organization-not-found"));
        mockMvc.perform(get("/api/v1/members").with(as(platformAdmin.id())).with(org(someOrg)))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokingThePlatformFlagTakesEffectOnTheNextRequest() throws Exception {
        var platformAdmin = data().newPlatformAdmin();
        mockMvc.perform(get("/api/v1/platform/organizations").with(as(platformAdmin.id())))
                .andExpect(status().isOk());

        jdbc.update("update app_user set is_platform_admin = false where id = ?", platformAdmin.id());

        // The flag is read from the database on every request, never carried in the token.
        mockMvc.perform(get("/api/v1/platform/organizations").with(as(platformAdmin.id())))
                .andExpect(status().isForbidden());
    }
}
