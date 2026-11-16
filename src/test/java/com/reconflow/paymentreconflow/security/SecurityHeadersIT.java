package com.reconflow.paymentreconflow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * M10 cross-cutting controls: response headers for a JSON API, an explicit CORS allow-list, and an
 * actuator that exposes only what operations need.
 */
class SecurityHeadersIT extends AbstractIntegrationTest {

    @Test
    void anApiResponseCarriesTheSecurityHeaders() throws Exception {
        UUID org = data().newOrganization();
        UUID viewer = data().newMember(org, Role.VIEWER).id();

        mockMvc.perform(get("/api/v1/exceptions").with(as(viewer)).secure(true))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
    }

    @Test
    void errorsCarryThemToo() throws Exception {
        mockMvc.perform(get("/api/v1/exceptions"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void hstsIsOnlySentOverHttps() throws Exception {
        // Over plain HTTP the header means nothing and browsers ignore it; Spring omits it.
        mockMvc.perform(get("/actuator/health")).andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void corsAllowsOnlyTheConfiguredOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/exceptions")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization, X-Organization-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));

        mockMvc.perform(options("/api/v1/exceptions")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "configprops", "heapdump", "threaddump", "loggers", "shutdown", "beans", "mappings"})
    void sensitiveActuatorEndpointsDoNotExistEvenForAnAuthenticatedUser(String endpoint) throws Exception {
        UUID user = data().newUser().id();

        mockMvc.perform(get("/actuator/" + endpoint).with(as(user))).andExpect(status().isNotFound());
    }

    @Test
    void healthShowsNoComponentDetails() throws Exception {
        UUID user = data().newUser().id();

        // Not even to a logged-in user: since M10.1 any Keycloak account would be "authorized".
        mockMvc.perform(get("/actuator/health").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
