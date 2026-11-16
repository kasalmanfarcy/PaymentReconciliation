package com.reconflow.paymentreconflow.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.security.token.RefreshCookies;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestData;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * M3.3j, the TASKS.md checklist end to end over HTTP, starting from a real login wherever login can
 * produce the case. The detailed variants live next to each feature: ResourceServerIT (token
 * validation), BruteForceProtectionIT (lockout, identical errors, rate limit), RefreshTokenServiceIT
 * (rotation races), SecurityAuditIT (audit rows).
 */
class AuthenticationChecklistIT extends AbstractIntegrationTest {

    private record Session(String accessToken, String refreshToken) {}

    private Session realLogin(String email) throws Exception {
        MvcResult result =
                login(email, TestData.PASSWORD).andExpect(status().isOk()).andReturn();
        return new Session(
                jsonMapper
                        .readTree(result.getResponse().getContentAsString())
                        .get("accessToken")
                        .asString(),
                result.getResponse().getCookie(RefreshCookies.NAME).getValue());
    }

    private ResultActions listMembers(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + accessToken));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(RefreshCookies.NAME, refreshToken)));
    }

    @Test
    void aRealTokenWorksAndAnExpiredOneIs401() throws Exception {
        var admin = data().newMember(data().newOrganization(), Role.ORG_ADMIN);
        listMembers(realLogin(admin.email()).accessToken()).andExpect(status().isOk());

        // Login never issues an expired token, so this one is minted with the app's own key.
        String expired = tokens().forUser(
                        admin.id(),
                        c -> c.issuedAt(Instant.now().minusSeconds(1800))
                                .expiresAt(Instant.now().minusSeconds(900)));
        listMembers(expired)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void aTamperedRealTokenIs401() throws Exception {
        var admin = data().newMember(data().newOrganization(), Role.ORG_ADMIN);
        String[] parts = realLogin(admin.email()).accessToken().split("\\.");

        // Swap in another user's subject: a forged identity with the original signature.
        String otherPayload = tokens().forUser(data().newUser().id()).split("\\.")[1];
        listMembers(parts[0] + "." + otherPayload + "." + parts[2]).andExpect(status().isUnauthorized());
        // Flip one character of the signature itself.
        char[] signature = parts[2].toCharArray();
        signature[5] = signature[5] == 'x' ? 'y' : 'x';
        listMembers(parts[0] + "." + parts[1] + "." + new String(signature)).andExpect(status().isUnauthorized());
    }

    @Test
    void aReusedRefreshTokenRevokesItsWholeFamily() throws Exception {
        var user = data().newUser();
        Session session = realLogin(user.email());
        String rotated = refresh(session.refreshToken())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(RefreshCookies.NAME)
                .getValue();

        refresh(session.refreshToken()).andExpect(status().isUnauthorized()); // the replay
        refresh(rotated).andExpect(status().isUnauthorized()); // so the current one dies too
    }

    @Test
    void lockoutTriggersAfterFiveFailures() throws Exception {
        var user = data().newUser();
        for (int i = 1; i <= LoginAttemptService.MAX_FAILURES; i++) {
            login(user.email(), "guess-number-" + i).andExpect(status().isUnauthorized());
        }

        login(user.email(), TestData.PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUserAndWrongPasswordGetTheSameError() throws Exception {
        var user = data().newUser();

        String wrong = login(user.email(), "wrong-password-here")
                .andReturn()
                .getResponse()
                .getContentAsString();
        String unknown = login("ghost-" + user.id() + "@example.test", "wrong-password-here")
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(LoginIT.withoutRequestId(unknown)).isEqualTo(LoginIT.withoutRequestId(wrong));
    }
}
