package com.reconflow.paymentreconflow.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

/** The filter is registered in the real application, not only callable in a unit test. */
class RequestIdIT extends AbstractIntegrationTest {

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(
                        header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f]{16}")));
    }

    @Test
    void aSafeIncomingIdIsEchoed() throws Exception {
        mockMvc.perform(get("/actuator/health").header(RequestIdFilter.HEADER, "trace-me-123"))
                .andExpect(header().string(RequestIdFilter.HEADER, "trace-me-123"));
    }

    @Test
    void anUnsafeIncomingIdIsReplaced() throws Exception {
        mockMvc.perform(get("/actuator/health").header(RequestIdFilter.HEADER, "bad\nvalue"))
                .andExpect(
                        header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern("[0-9a-f]{16}")));
    }
}
