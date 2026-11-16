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
import com.reconflow.paymentreconflow.support.TestData;
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
    void anAdminCreatesAMemberWithATemporaryPasswordThatMustBeChanged() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        String email = "new-" + UUID.randomUUID() + "@example.test";

        String body = createMember(admin.id(), org, email, Role.UPLOADER)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/members/")))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("UPLOADER"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String temporaryPassword =
                jsonMapper.readTree(body).get("temporaryPassword").asString();

        assertThat(temporaryPassword).hasSize(16);
        // The new user can log in with it, and must change it before anything else.
        String token = jsonMapper
                .readTree(login(email, temporaryPassword)
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("accessToken")
                .asString();
        mockMvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("password-change-required"));
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
        // Their password is untouched.
        login(elsewhere.email(), TestData.PASSWORD).andExpect(status().isOk());
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
        login(member.email(), TestData.PASSWORD).andExpect(status().isOk());

        String body = mockMvc.perform(post("/api/v1/members/" + member.id() + "/reset-password")
                        .with(as(admin.id())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String temporaryPassword =
                jsonMapper.readTree(body).get("temporaryPassword").asString();

        login(member.email(), TestData.PASSWORD).andExpect(status().isUnauthorized());
        login(member.email(), temporaryPassword).andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                        "select count(*) from refresh_token where user_id = ? and revoked_reason = 'ADMIN_REVOKED'",
                        Integer.class,
                        member.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select must_change_password from app_user where id = ?", Boolean.class, member.id()))
                .isTrue();
    }

    @Test
    void aDisabledMemberIsOutImmediately() throws Exception {
        UUID org = data().newOrganization();
        var admin = data().newMember(org, Role.ORG_ADMIN);
        var member = data().newMember(org, Role.ORG_ADMIN);
        login(member.email(), TestData.PASSWORD).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/members/" + member.id() + "/disable").with(as(admin.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(get("/api/v1/members").with(as(member.id()))).andExpect(status().isUnauthorized());
        login(member.email(), TestData.PASSWORD).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                        "select count(*) from refresh_token where user_id = ? and revoked_reason = 'USER_DISABLED'",
                        Integer.class,
                        member.id()))
                .isEqualTo(1);
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
