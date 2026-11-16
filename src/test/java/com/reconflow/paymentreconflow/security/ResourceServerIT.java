package com.reconflow.paymentreconflow.security;

import static com.reconflow.paymentreconflow.support.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * M3.3e, M10.1: the app validates Keycloak's access tokens (here signed by the stand-in
 * {@code TestIssuer}, through the real issuer-uri decoder) and maps their subject to a user; every
 * rejection is the same ProblemDetail 401.
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
        var user = data().newMember(data().newOrganization(), Role.VIEWER);

        // Past security and tenant resolution: the path simply doesn't exist.
        probe(tokens().forUser(user.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
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
    void aTokenFromAnotherIssuerSignedWithItsOwnKeyIs401() throws Exception {
        var user = data().newUser();
        // What another realm (or another IdP) would issue: its own iss and its own key.
        String token = tokens().forUser(user.id(), c -> c.issuer("http://localhost:1/realms/other"));

        assertUnauthenticated(probe(token));
        assertUnauthenticated(probe(tokens().signedWithAnotherKey(user.id())));
    }

    @Test
    void aValidTokenForAnAccountReconFlowDoesNotKnowIs401() throws Exception {
        // Correctly signed, right issuer and audience, but no app_user has this subject and there
        // is no verified email to link by: authenticated at Keycloak, unknown here.
        assertUnauthenticated(probe(tokens().forSubject("unknown-" + UUID.randomUUID(), c -> {})));
    }

    @Test
    void aVerifiedEmailLinksAPreProvisionedUserOnceByItsSubject() throws Exception {
        var user = data().newUnlinkedUser("first-login-" + UUID.randomUUID() + "@example.test");
        String subject = "kc-" + UUID.randomUUID();

        probe(tokens().forSubject(
                                subject,
                                c -> c.claim("email", user.email().toUpperCase())
                                        .claim("email_verified", true)))
                .andExpect(status().isNotFound()); // authenticated: past security to MVC's 404

        assertThat(jdbc.queryForObject("select external_subject from app_user where id = ?", String.class, user.id()))
                .isEqualTo(subject);
        // From now on the subject alone is enough; the email is never consulted again.
        probe(tokens().forSubject(subject, c -> {})).andExpect(status().isNotFound());
    }

    @Test
    void anUnverifiedEmailNeverLinks() throws Exception {
        // Anyone who can register this address at the IdP without proving it would otherwise become
        // this user: an account takeover.
        var user = data().newUnlinkedUser("unverified-" + UUID.randomUUID() + "@example.test");

        assertUnauthenticated(probe(tokens().forSubject(
                        "kc-" + UUID.randomUUID(),
                        c -> c.claim("email", user.email()).claim("email_verified", false))));
        assertUnauthenticated(
                probe(tokens().forSubject("kc-" + UUID.randomUUID(), c -> c.claim("email", user.email()))));
        assertThat(jdbc.queryForObject("select external_subject from app_user where id = ?", String.class, user.id()))
                .isNull();
    }

    @Test
    void anAlreadyLinkedUserIsNeverRelinkedToAnotherSubject() throws Exception {
        var user = data().newUser(); // linked: external_subject = its id

        assertUnauthenticated(probe(tokens().forSubject(
                        "kc-" + UUID.randomUUID(),
                        c -> c.claim("email", user.email()).claim("email_verified", true))));
        assertThat(jdbc.queryForObject("select external_subject from app_user where id = ?", String.class, user.id()))
                .isEqualTo(user.id().toString());
    }
}
