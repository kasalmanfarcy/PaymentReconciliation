package com.reconflow.paymentreconflow.exceptions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** Shared steps for the /exceptions API tests: build cases through a real run, call each endpoint. */
abstract class AbstractExceptionApiIT extends AbstractIntegrationTest {

    static final String EXCEPTIONS = "/api/v1/exceptions";
    static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

    @Autowired
    protected JobRunner jobRunner;

    protected ExceptionFixture fixture() {
        return new ExceptionFixture(jdbc, jobRunner);
    }

    protected MockHttpServletResponse perform(MockHttpServletRequestBuilder req, ExceptionFixture f, UUID user)
            throws Exception {
        return mockMvc.perform(req.with(as(user)).with(org(f.org))).andReturn().getResponse();
    }

    protected MockHttpServletResponse detail(ExceptionFixture f, UUID caseId, UUID user) throws Exception {
        return perform(get(EXCEPTIONS + "/" + caseId), f, user);
    }

    protected MockHttpServletResponse list(ExceptionFixture f, UUID user, String query) throws Exception {
        return perform(get(EXCEPTIONS + (query.isEmpty() ? "" : "?" + query)), f, user);
    }

    protected MockHttpServletResponse patchCase(
            ExceptionFixture f, UUID caseId, UUID user, @Nullable String ifMatch, String body) throws Exception {
        MockHttpServletRequestBuilder req =
                patch(EXCEPTIONS + "/" + caseId).contentType(MERGE_PATCH).content(body);
        if (ifMatch != null) {
            req.header("If-Match", ifMatch);
        }
        return perform(req, f, user);
    }

    protected MockHttpServletResponse addNote(ExceptionFixture f, UUID caseId, UUID user, String body)
            throws Exception {
        return perform(
                post(EXCEPTIONS + "/" + caseId + "/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("body", body))),
                f,
                user);
    }

    protected MockHttpServletResponse resolve(
            ExceptionFixture f, UUID caseId, UUID user, @Nullable String ifMatch, String code, @Nullable String note)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("resolutionCode", code);
        body.put("note", note);
        MockHttpServletRequestBuilder req = post(EXCEPTIONS + "/" + caseId + "/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (ifMatch != null) {
            req.header("If-Match", ifMatch);
        }
        return perform(req, f, user);
    }

    protected MockHttpServletResponse reopen(
            ExceptionFixture f, UUID caseId, UUID user, @Nullable String ifMatch, @Nullable String reason)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("reason", reason);
        MockHttpServletRequestBuilder req = post(EXCEPTIONS + "/" + caseId + "/reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body));
        if (ifMatch != null) {
            req.header("If-Match", ifMatch);
        }
        return perform(req, f, user);
    }

    /** The current version as an If-Match value: {@code "3"}. */
    protected static String etag(ExceptionFixture f, UUID caseId) {
        return "\"" + f.version(caseId) + "\"";
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> json(MockHttpServletResponse res) throws Exception {
        return jsonMapper.readValue(res.getContentAsString(), Map.class);
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> content(MockHttpServletResponse res) throws Exception {
        return (List<Map<String, Object>>) json(res).get("content");
    }

    protected static String code(Map<String, Object> problem) {
        return (String) problem.get("code");
    }
}
