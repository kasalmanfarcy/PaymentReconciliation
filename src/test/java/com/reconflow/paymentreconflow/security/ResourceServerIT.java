package com.reconflow.paymentreconflow.security;

import static com.reconflow.paymentreconflow.support.TestTokens.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * M3.3e: the app validates its own JWTs, and every rejection is the same ProblemDetail 401.
 *
 * <p>"Authenticated" is probed with {@code GET /api/v1/no-such-endpoint}: a valid token gets past
 * security to MVC's 404, an invalid one stops at 401.
 */
class ResourceServerIT extends AbstractIntegrationTest {

    private static final String PROBE = "/api/v1/no-such-endpoint";

    private ResultActions probe(String token) throws Exception {
        return mockMvc.perform(get(PROBE).header("Authorization", bearer(token)));
    }

    private static void assertUnauthenticated(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthenticated"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                // Ours, not the default BearerTokenAuthenticationEntryPoint's error description.
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void aValidTokenIsAccepted() throws Exception {
        var user = data().newUser();

        // Past security: the path simply doesn't exist.
        probe(tokens().forUser(user.id())).andExpect(status().isNotFound());
    }

    @Test
    void noTokenIs401() throws Exception {
        assertUnauthenticated(mockMvc.perform(get(PROBE)));
    }

    @Test
    void anExpiredTokenIs401() throws Exception {
        var user = data().newUser();
        // Beyond the 60 s clock-skew allowance.
        String expired = tokens().forUser(
                        user.id(),
                        c -> c.issuedAt(Instant.now().minusSeconds(3600))
                                .expiresAt(Instant.now().minusSeconds(120)));

        assertUnauthenticated(probe(expired));
    }

    @Test
    void aTokenSignedWithAnotherKeyIs401() throws Exception {
        var user = data().newUser();

        assertUnauthenticated(probe(tokens().signedWithAnotherKey(user.id())));
    }

    @Test
    void aTamperedPayloadIs401() throws Exception {
        var user = data().newUser();
        String token = tokens().forUser(user.id());
        String[] parts = token.split("\\.");
        // Flip one character of the payload: the signature no longer matches it.
        char[] payload = parts[1].toCharArray();
        payload[10] = payload[10] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertUnauthenticated(probe(tampered));
    }

    @Test
    void theWrongAudienceOrIssuerIs401() throws Exception {
        var user = data().newUser();

        assertUnauthenticated(probe(tokens().forUser(user.id(), c -> c.audience(List.of("some-other-api")))));
        assertUnauthenticated(probe(tokens().forUser(user.id(), c -> c.issuer("https://evil.example"))));
    }

    @Test
    void garbageIs401() throws Exception {
        assertUnauthenticated(probe("not.a.jwt"));
    }

    @Test
    void aPendingPasswordChangeBlocksEverythingButChangePasswordAndLogout() throws Exception {
        var user = data().newUser();
        String token = tokens().forUser(user.id(), c -> c.claim("pwd_change", true));

        mockMvc.perform(get(PROBE).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("password-change-required"));
        // Allowed through (and then fails validation on the empty body, not on the gate).
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
    }
}
