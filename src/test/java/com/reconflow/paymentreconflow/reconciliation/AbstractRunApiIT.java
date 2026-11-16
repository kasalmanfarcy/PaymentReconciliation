package com.reconflow.paymentreconflow.reconciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Shared steps for the run API tests: request a run as the fixture's investigator, read it back. */
abstract class AbstractRunApiIT extends AbstractIntegrationTest {

    static final String RUNS = "/api/v1/reconciliation-runs";

    @Autowired
    protected JobRunner jobRunner;

    protected static String body(String from, String to, String currency) {
        return "{\"periodStart\":\"" + from + "\",\"periodEnd\":\"" + to + "\",\"currency\":\"" + currency + "\"}";
    }

    protected MockHttpServletResponse requestRun(ReconFixture f, String body, @Nullable String key) throws Exception {
        MockHttpServletRequestBuilder req = post(RUNS)
                .with(as(f.user))
                .with(org(f.org))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mockMvc.perform(req).andReturn().getResponse();
    }

    /** Requests a run, expects 202, runs the queue for the organization; returns the run id. */
    protected UUID runToCompletion(ReconFixture f, String from, String to, String currency) throws Exception {
        MockHttpServletResponse res =
                requestRun(f, body(from, to, currency), UUID.randomUUID().toString());
        if (res.getStatus() != 202) {
            throw new AssertionError("expected 202, got " + res.getStatus() + ": " + res.getContentAsString());
        }
        jobRunner.runAllQueued(List.of(f.org));
        return UUID.fromString(json(res).get("id").toString());
    }

    protected MockHttpServletResponse getAs(ReconFixture f, String path) throws Exception {
        return mockMvc.perform(get(path).with(as(f.user)).with(org(f.org)))
                .andReturn()
                .getResponse();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> json(MockHttpServletResponse res) throws Exception {
        return jsonMapper.readValue(res.getContentAsString(), Map.class);
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
