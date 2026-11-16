package com.reconflow.paymentreconflow.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestData;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** M3.3b: login issues an RS256 access token whose claims carry identity only. */
class LoginIT extends AbstractIntegrationTest {

    @Autowired
    JwtDecoder decoder;

    @Test
    void aUserLogsInAndTheTokenDecodesWithThePublicKey() throws Exception {
        UUID org = data().newOrganization("Acme Login");
        var user = data().newMember(org, Role.INVESTIGATOR);

        MvcResult result = login(user.email().toUpperCase(), TestData.PASSWORD) // email is case-insensitive
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.memberships[0].organizationId").value(org.toString()))
                .andExpect(jsonPath("$.memberships[0].name").value("Acme Login"))
                .andExpect(jsonPath("$.memberships[0].role").value("INVESTIGATOR"))
                .andReturn();

        String token = jsonMapper
                .readTree(result.getResponse().getContentAsString())
                .get("accessToken")
                .asString();
        Jwt jwt = decoder.decode(token); // signature, expiry, iss and aud all checked
        assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("reconflow");
        assertThat(jwt.getAudience()).containsExactly("reconflow-api");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsKey("kid");
        // Identity only: no roles, no organization, no platform flag, no pending password change.
        assertThat(jwt.getClaims()).doesNotContainKeys("roles", "org", "organizationId", "pwd_change", "scope");
    }

    @Test
    void loginSetsTheRefreshCookieWithItsSecurityAttributes() throws Exception {
        var user = data().newUser();
        String setCookie = login(user.email(), TestData.PASSWORD)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("Set-Cookie");

        assertThat(setCookie)
                .startsWith("refresh_token=")
                .contains("Path=/api/v1/auth", "Max-Age=604800", "HttpOnly", "Secure", "SameSite=Strict");
        String raw = setCookie.substring("refresh_token=".length(), setCookie.indexOf(';'));
        assertThat(jdbc.queryForObject(
                        "select count(*) from refresh_token where user_id = ? and token_hash = encode(sha256(?::bytea), 'hex')",
                        Integer.class,
                        user.id(),
                        raw))
                .isEqualTo(1);
    }

    @Test
    void aPendingPasswordChangeIsAClaim() throws Exception {
        var user = data().newUser();
        data().requirePasswordChange(user.id());

        String token = accessToken(login(user.email(), TestData.PASSWORD).andReturn());

        assertThat(decoder.decode(token).getClaims()).containsEntry("pwd_change", true);
    }

    @Test
    void lastLoginIsRecorded() throws Exception {
        var user = data().newUser();

        login(user.email(), TestData.PASSWORD).andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                        "select last_login_at is not null from app_user where id = ?", Boolean.class, user.id()))
                .isTrue();
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveByteIdenticalBodies() throws Exception {
        var user = data().newUser();

        String wrongPassword = body(login(user.email(), "not-the-password-at-all")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("invalid-credentials"))
                .andExpect(jsonPath("$.title").value("Invalid email or password"))
                .andReturn());
        String unknownEmail = body(login("nobody-" + UUID.randomUUID() + "@example.test", "not-the-password-at-all")
                .andExpect(status().isUnauthorized())
                .andReturn());

        assertThat(withoutRequestId(unknownEmail)).isEqualTo(withoutRequestId(wrongPassword));
    }

    @Test
    void blankCredentialsAreAValidationFailure() throws Exception {
        login("", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
    }

    private String accessToken(MvcResult result) throws Exception {
        JsonNode json = jsonMapper.readTree(result.getResponse().getContentAsString());
        return json.get("accessToken").asString();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /** requestId differs per request by design; everything else must match byte for byte. */
    static String withoutRequestId(String body) {
        return body.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"-\"");
    }
}
