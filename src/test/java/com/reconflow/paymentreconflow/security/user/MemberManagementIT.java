package com.reconflow.paymentreconflow.security.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** M3.3g: admin-created users only, with temporary passwords, and the last-admin guard. */
class MemberManagementIT extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    AccountProvisioning provisioning;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    private ResultActions createMember(UUID admin, UUID org, String email, Role role) throws Exception {
        return mockMvc.perform(post("/api/v1/members")
                .with(as(admin))
                .with(org(org))
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(
                        Map.of("email", email, "displayName", "New Person", "role", role.name()))));
    }

    private ResultActions changeRole(UUID admin, UUID org, UUID target, Role role) throws Exception {
        return mockMvc.perform(patch("/api/v1/members/" + target)
                .with(as(admin))
                .with(org(org))
                .contentType("application/merge-patch+json")
                .content("{\"role\":\"" + role + "\"}"));
    }

    @Test
    void anAdminCreatesAMemberWhoseKeycloakAccountHasATemporaryPassword() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        String email = "new-" + UUID.randomUUID() + "@example.test";

        String body = createMember(admin.id(), org, email, Role.UPLOADER)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/members/")))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("UPLOADER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String temporaryPassword =
                jsonMapper.readTree(body).get("temporaryPassword").asString();
        UUID userId = UUID.fromString(jsonMapper.readTree(body).get("userId").asString());

        assertThat(temporaryPassword).hasSize(16);
        // M10.1: the account was created in the identity provider with that temporary password
        // (Keycloak's "Update Password" required action makes it single-use), and linked by sub.
        String subject =
                jdbc.queryForObject("select external_subject from app_user where id = ?", String.class, userId);
        assertThat(idp.account(subject)).satisfies(a -> {
            assertThat(a.email()).isEqualTo(email);
            assertThat(a.password()).isEqualTo(temporaryPassword);
        });
        assertThat(jdbc.queryForObject("select password_hash from app_user where id = ?", String.class, userId))
                .as("no password is stored in ReconFlow any more")
                .isNull();
        // A token for that subject is the new member.
        mockMvc.perform(get("/api/v1/imports")
                        .header("Authorization", TestTokens.bearer(tokens().forSubject(subject, c -> {}))))
                .andExpect(status().isOk());
    }

    @Test
    void ifTheDatabaseHalfRollsBackTheKeycloakAccountIsDeletedAgain() {
        // No transaction spans Keycloak and PostgreSQL: AccountProvisioning compensates instead.
        String email = "rolled-back-" + UUID.randomUUID() + "@example.test";

        String subject = tx.execute(status -> {
            String created = provisioning.provision(email, "Rolled Back").subject();
            status.setRollbackOnly(); // e.g. the app_user insert lost a unique-email race
            return created;
        });

        assertThat(idp.calls()).contains("create:" + subject, "delete:" + subject);
        assertThat(idp.exists(subject)).isFalse();
    }

    @Test
    void aCommittedProvisioningIsKept() {
        String email = "kept-" + UUID.randomUUID() + "@example.test";

        String subject =
                tx.execute(status -> provisioning.provision(email, "Kept").subject());

        assertThat(idp.exists(subject)).isTrue();
        assertThat(idp.calls()).doesNotContain("delete:" + subject);
    }

    @Test
    void anExistingAccountIsAttachedWithoutANewPassword() throws Exception {
        UUID orgA = data().newOrganization();
        UUID orgB = data().newOrganization();
        var admin = data().newMember(orgA, Role.ORG_ADMIN);
        var elsewhere = data().newMember(orgB, Role.VIEWER);

        createMember(admin.id(), orgA, elsewhere.email().toUpperCase(), Role.INVESTIGATOR)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(elsewhere.id().toString()))
                .andExpect(jsonPath("$.temporaryPassword").value(nullValue()));
        // No new identity-provider account: the person already has one.
        assertThat(idp.calls()).noneMatch(call -> call.contains(elsewhere.id().toString()));
    }

    @Test
    void addingAnExistingMemberIs409() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        var member = data().newMember(org, Role.VIEWER);

        createMember(admin.id(), org, member.email(), Role.VIEWER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already-a-member"));
    }

    @Test
    void invalidBodiesAreValidationFailures() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);

        mockMvc.perform(post("/api/v1/members")
                        .with(as(admin.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"displayName\":\"\",\"role\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors", hasSize(3)));
        // A client can't smuggle in fields the request record doesn't have.
        mockMvc.perform(post("/api/v1/members")
                        .with(as(admin.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x-" + UUID.randomUUID()
                                + "@example.test\",\"displayName\":\"X\",\"role\":\"VIEWER\",\"platformAdmin\":true}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject(
                        "select count(*) from app_user where is_platform_admin and email like 'x-%'", Integer.class))
                .isZero();
    }

    @Test
    void listIsPagedSortableAndOnlyThisOrganization() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        data().newMember(org, Role.VIEWER);
        data().newMember(org, Role.UPLOADER);
        data().newMember(data().newOrganization(), Role.VIEWER); // elsewhere

        mockMvc.perform(get("/api/v1/members").with(as(admin.id())).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.size").value(2));
        mockMvc.perform(get("/api/v1/members").with(as(admin.id())).param("size", "1000"))
                .andExpect(jsonPath("$.page.size").value(200)); // clamped, not rejected
        mockMvc.perform(get("/api/v1/members").with(as(admin.id())).param("sort", "role,asc"))
                .andExpect(jsonPath("$.content[0].role").value("ORG_ADMIN"));
        mockMvc.perform(get("/api/v1/members").with(as(admin.id())).param("sort", "password_hash"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
    }

    @Test
    void roleChangesApplyOnTheNextRequest() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        var viewer = data().newMember(org, Role.VIEWER);
        mockMvc.perform(get("/api/v1/members").with(as(viewer.id()))).andExpect(status().isForbidden());

        changeRole(admin.id(), org, viewer.id(), Role.ORG_ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORG_ADMIN"));

        // Same kind of token as before: permissions are looked up per request.
        mockMvc.perform(get("/api/v1/members").with(as(viewer.id()))).andExpect(status().isOk());
    }

    @Test
    void theLastActiveAdminCanBeNeitherDemotedNorDisabled() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        var disabledAdmin = data().newMember(org, Role.ORG_ADMIN);
        data().setStatus(disabledAdmin.id(), "DISABLED"); // doesn't count

        changeRole(admin.id(), org, admin.id(), Role.VIEWER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last-org-admin"));
        mockMvc.perform(post("/api/v1/members/" + admin.id() + "/disable").with(as(admin.id())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("last-org-admin"));
    }

    @Test
    void twoAdminsDemotingEachOtherAtOnceLeaveOneAdmin() throws Exception {
        UUID org = data().newOrganization();
        var a = data().newMember(org, Role.ORG_ADMIN);
        var b = data().newMember(org, Role.ORG_ADMIN);
        var start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (var pair : List.of(List.of(a.id(), b.id()), List.of(b.id(), a.id()))) {
                statuses.add(pool.submit(() -> {
                    start.await();
                    return changeRole(pair.get(0), org, pair.get(1), Role.VIEWER)
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
        }

        assertThat(List.of(statuses.get(0).get(), statuses.get(1).get())).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject(
                        "select count(*) from membership where organization_id = ? and role = 'ORG_ADMIN'",
                        Integer.class,
                        org))
                .isEqualTo(1);
    }

    @Test
    void resetPasswordIssuesATemporaryOneAndEndsEverySession() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        var member = data().newMember(org, Role.INVESTIGATOR);

        String body = mockMvc.perform(post("/api/v1/members/" + member.id() + "/reset-password")
                        .with(as(admin.id())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String temporaryPassword =
                jsonMapper.readTree(body).get("temporaryPassword").asString();

        // Keycloak sets it as temporary and logs the account out (which revokes refresh tokens).
        assertThat(idp.calls()).contains("reset:" + member.id());
        assertThat(temporaryPassword).hasSize(16);
    }

    @Test
    void resetPasswordForAUserFromBeforeKeycloakCreatesAndLinksTheirAccount() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        var legacy = data().newUnlinkedUser("legacy-" + UUID.randomUUID() + "@example.test");
        data().addMembership(legacy.id(), org, Role.VIEWER);

        String body = mockMvc.perform(post("/api/v1/members/" + legacy.id() + "/reset-password")
                        .with(as(admin.id())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String subject =
                jdbc.queryForObject("select external_subject from app_user where id = ?", String.class, legacy.id());
        assertThat(subject).isNotNull();
        assertThat(idp.account(subject).password())
                .isEqualTo(jsonMapper.readTree(body).get("temporaryPassword").asString());
    }

    @Test
    void aDisabledMemberIsOutImmediatelyHereAndAtTheIdentityProvider() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        var member = data().newMember(org, Role.ORG_ADMIN);
        mockMvc.perform(get("/api/v1/members").with(as(member.id()))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/members/" + member.id() + "/disable").with(as(admin.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // A token issued before the disable is refused on the very next request...
        mockMvc.perform(get("/api/v1/members").with(as(member.id()))).andExpect(status().isUnauthorized());
        // ...and Keycloak won't issue or refresh another.
        assertThat(idp.calls()).contains("disable:" + member.id());
    }

    @Test
    void thereIsNoSignUpEndpoint() throws Exception {
        var user = data().newMember(data().newOrganization(), Role.VIEWER);
        String body = "{\"email\":\"x@example.test\",\"password\":\"a-long-enough-password\"}";

        // Not public, and not there for anyone: users are only ever created by an admin.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(as(user.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }
}
