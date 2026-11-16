package com.reconflow.paymentreconflow.security;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.web.RequestIdFilter;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Health is public; without a bearer token everything else is a ProblemDetail 401 (M2.2, M3.3e). */
class SecurityConfigIT extends AbstractIntegrationTest {

    @Test
    void healthIsPublicAndShowsNoDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // show-details: when-authorized; component details would help an attacker.
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void everythingElseIsUnauthenticatedAsAProblemDetail() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://reconflow.dev/problems/unauthenticated"))
                .andExpect(jsonPath("$.code").value("unauthenticated"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/anything"))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[0-9a-f]{16}")))
                .andReturn();

        // The body's requestId is the header's value, so a user can quote either.
        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER);
        mockMvc.perform(get("/api/v1/anything").header(RequestIdFilter.HEADER, requestId))
                .andExpect(jsonPath("$.requestId").value(requestId));
    }

    @Test
    void writesAreUnauthenticatedToo() throws Exception {
        // CSRF is off (stateless API), so a POST gets the same 401, not a CSRF 403.
        mockMvc.perform(post("/api/v1/imports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void httpBasicIsNotOffered() throws Exception {
        mockMvc.perform(get("/api/v1/anything").header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }
}
