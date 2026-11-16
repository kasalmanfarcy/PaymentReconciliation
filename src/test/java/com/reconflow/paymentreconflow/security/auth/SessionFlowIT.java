package com.reconflow.paymentreconflow.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.security.token.RefreshCookies;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestData;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** M3.3d: the whole session lifecycle over HTTP. */
class SessionFlowIT extends AbstractIntegrationTest {

    @Autowired
    JwtDecoder decoder;

    @Test
    void loginRefreshRefreshLogoutThenRefreshFails() throws Exception {
        var user = data().newUser();

        String c1 = refreshCookie(login(user.email(), TestData.PASSWORD)
                .andExpect(status().isOk())
                .andReturn());
        String c2 = refreshCookie(refresh(c1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn());
        String c3 = refreshCookie(refresh(c2).andExpect(status().isOk()).andReturn());
        assertThat(c3).isNotEqualTo(c2).isNotEqualTo(c1);

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout").cookie(cookie(c3)))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(logout.getResponse().getHeader("Set-Cookie")).contains("refresh_token=;", "Max-Age=0");

        refresh(c3)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
    }

    @Test
    void refreshWithoutACookieIsUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout").cookie(cookie("not-a-real-token")))
                .andExpect(status().isNoContent());
    }

    @Test
    void changingAPendingPasswordReturnsAFreshPairAndLogsOutOtherDevices() throws Exception {
        var user = data().newUser();
        data().requirePasswordChange(user.id());
        MvcResult laptop = login(user.email(), TestData.PASSWORD).andReturn();
        MvcResult phone = login(user.email(), TestData.PASSWORD).andReturn();
        assertThat(decoder.decode(accessToken(laptop)).getClaims()).containsEntry("pwd_change", true);

        MvcResult changed = changePassword(accessToken(laptop), TestData.PASSWORD, "a-brand-new-passphrase")
                .andExpect(status().isOk())
                .andReturn();

        // The new access token no longer carries pwd_change, and the new cookie works.
        assertThat(decoder.decode(accessToken(changed)).getClaims()).doesNotContainKey("pwd_change");
        refresh(refreshCookie(changed)).andExpect(status().isOk());
        // Every older session (both devices' original cookies) is revoked, without a reuse alarm.
        refresh(refreshCookie(laptop)).andExpect(status().isUnauthorized());
        refresh(refreshCookie(phone)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                        "select count(*) from refresh_token where user_id = ? and revoked_reason = 'PASSWORD_CHANGED'",
                        Integer.class,
                        user.id()))
                .isEqualTo(2);
        login(user.email(), "a-brand-new-passphrase").andExpect(status().isOk());
        login(user.email(), TestData.PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void changePasswordChecksTheCurrentPasswordAndThePolicy() throws Exception {
        var user = data().newUser();
        String token = accessToken(login(user.email(), TestData.PASSWORD).andReturn());

        changePassword(token, "not-my-password-at-all", "a-brand-new-passphrase")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid-credentials"));
        changePassword(token, TestData.PASSWORD, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("password-policy"));
    }

    @Test
    void changePasswordNeedsABearerToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions refresh(String cookieValue) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh").cookie(cookie(cookieValue)));
    }

    private ResultActions changePassword(String accessToken, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/change-password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
    }

    private static Cookie cookie(String value) {
        return new Cookie(RefreshCookies.NAME, value);
    }

    private static String refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(RefreshCookies.NAME);
        assertThat(cookie).as("refresh cookie").isNotNull();
        return cookie.getValue();
    }

    private String accessToken(MvcResult result) throws Exception {
        return jsonMapper
                .readTree(result.getResponse().getContentAsString())
                .get("accessToken")
                .asString();
    }
}
