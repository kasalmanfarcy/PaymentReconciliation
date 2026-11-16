package com.reconflow.paymentreconflow.security.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.web.RequestIdFilter;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** M3.3f: lockout, identical errors, and the per-IP rate limit. */
class BruteForceProtectionIT extends AbstractIntegrationTest {

    @Autowired
    AuthService auth;

    private Map<String, Object> lockState(UUID userId) {
        return jdbc.queryForMap("select failed_login_count, locked_until from app_user where id = ?", userId);
    }

    @Test
    void fiveFailuresLockTheAccountEvenAgainstTheCorrectPassword() throws Exception {
        var user = data().newUser();
        for (int i = 0; i < 5; i++) {
            login(user.email(), "wrong-password-" + i).andExpect(status().isUnauthorized());
        }

        assertThat(lockState(user.id()).get("locked_until")).isNotNull();
        // The sixth attempt, with the right password, is refused with the very same 401.
        login(user.email(), TestData.PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid-credentials"));
        // Failures during the lock change nothing (no lock extension, count stays at 5).
        login(user.email(), "another-wrong-one").andExpect(status().isUnauthorized());
        assertThat(lockState(user.id()).get("failed_login_count")).isEqualTo(5);
    }

    @Test
    void afterTheLockExpiresTheCorrectPasswordWorksAndTheCountResets() throws Exception {
        var user = data().newUser();
        for (int i = 0; i < 5; i++) {
            login(user.email(), "wrong-password-" + i);
        }
        jdbc.update("update app_user set locked_until = now() - interval '1 second' where id = ?", user.id());

        login(user.email(), TestData.PASSWORD).andExpect(status().isOk());

        assertThat(lockState(user.id())).containsEntry("failed_login_count", 0).containsEntry("locked_until", null);
    }

    @Test
    void theFirstFailureAfterAnExpiredLockStartsANewWindow() throws Exception {
        var user = data().newUser();
        for (int i = 0; i < 5; i++) {
            login(user.email(), "wrong-password-" + i);
        }
        jdbc.update("update app_user set locked_until = now() - interval '1 second' where id = ?", user.id());

        login(user.email(), "wrong-again");

        // Count restarts at 1 and the lock is cleared, instead of re-locking on one guess.
        assertThat(lockState(user.id())).containsEntry("failed_login_count", 1).containsEntry("locked_until", null);
    }

    @Test
    void unknownEmailWrongPasswordLockedAndDisabledAreIndistinguishable() throws Exception {
        var wrongPassword = data().newUser();
        var locked = data().newUser();
        jdbc.update("update app_user set locked_until = now() + interval '10 minutes' where id = ?", locked.id());
        var disabled = data().newUser();
        data().setStatus(disabled.id(), "DISABLED");

        var reference =
                login(wrongPassword.email(), "not-the-password").andReturn().getResponse();
        List<MockHttpServletResponse> others = List.of(
                login("nobody-" + UUID.randomUUID() + "@example.test", "not-the-password")
                        .andReturn()
                        .getResponse(),
                login(locked.email(), TestData.PASSWORD).andReturn().getResponse(),
                login(disabled.email(), TestData.PASSWORD).andReturn().getResponse());

        for (MockHttpServletResponse other : others) {
            assertThat(other.getStatus()).isEqualTo(reference.getStatus()).isEqualTo(401);
            assertThat(LoginIT.withoutRequestId(other.getContentAsString()))
                    .isEqualTo(LoginIT.withoutRequestId(reference.getContentAsString()));
            assertThat(headersWithoutRequestId(other)).isEqualTo(headersWithoutRequestId(reference));
        }
    }

    @Test
    void elevenRapidAttemptsFromOneIpGetA429OnTheEleventh() throws Exception {
        String ip = "192.0.2." + (1 + (int) (Math.random() * 250));
        String body = jsonMapper.writeValueAsString(
                Map.of("email", "nobody-" + UUID.randomUUID() + "@example.test", "password", "whatever-it-is"));
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(r -> {
                                r.setRemoteAddr(ip);
                                return r;
                            })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(r -> {
                            r.setRemoteAddr(ip);
                            return r;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate-limited"));
    }

    @Test
    void twentyConcurrentWrongPasswordsNeverCountPastTheLock() throws Exception {
        var user = data().newUser();
        var start = new CountDownLatch(1);
        List<Future<LoginResult>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return auth.login(user.email(), "wrong-password");
                }));
            }
            start.countDown();
        }
        for (Future<LoginResult> r : results) {
            assertThat(r.get()).isInstanceOf(LoginResult.Failure.class);
        }

        // A read-modify-write would lose increments (or count past the lock); the single UPDATE
        // serializes on the row and stops matching once locked.
        assertThat(lockState(user.id()).get("failed_login_count")).isEqualTo(5);
        assertThat(lockState(user.id()).get("locked_until")).isNotNull();
    }

    private static Map<String, String> headersWithoutRequestId(MockHttpServletResponse response) {
        Map<String, String> headers = new TreeMap<>();
        for (String name : response.getHeaderNames()) {
            if (!name.equalsIgnoreCase(RequestIdFilter.HEADER)) {
                headers.put(name, String.join(",", response.getHeaders(name)));
            }
        }
        return headers;
    }
}
