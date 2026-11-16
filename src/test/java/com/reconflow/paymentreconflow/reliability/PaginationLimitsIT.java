package com.reconflow.paymentreconflow.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * M9.8: no endpoint returns an unbounded list. Every list endpoint, asked for {@code size=1000},
 * answers a page of at most 200 (api-design.md §4: larger sizes are clamped, not rejected, and the
 * response says the effective size), and every list refuses a sort field it doesn't allow with
 * {@code 400 validation-failed} (an arbitrary sort on an unindexed column is the same denial of
 * service as an unbounded size), including lists with one fixed order.
 *
 * <p>The list of endpoints is kept by hand; {@link #everyListEndpointIsCovered} fails when a new
 * {@code GET} collection endpoint appears without being added here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaginationLimitsIT extends AbstractIntegrationTest {

    @Autowired
    JobRunner jobRunner;

    UUID org;
    UUID admin;
    UUID platformAdmin;
    UUID batch;

    final List<String> lists = new java.util.ArrayList<>();

    @BeforeAll
    void anOrganizationWithMoreThan200Rows() throws Exception {
        org = data().newOrganization();
        admin = data().newMember(org, Role.ORG_ADMIN).id();
        platformAdmin = data().newPlatformAdmin().id();
        StringBuilder ledger = new StringBuilder("reference,amount,currency,status,transaction_at\n");
        for (int i = 0; i < 300; i++) {
            ledger.append("PG-")
                    .append(i)
                    .append(",10.00,AED,SUCCESS,2026-10-0")
                    .append(1 + i % 3)
                    .append("T09:00:00Z\n");
            ledger.append("BAD-").append(i).append(",abc,AED,SUCCESS,2026-10-01T09:00:00Z\n");
        }
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile(
                                "file", "l.csv", "text/csv", ledger.toString().getBytes(StandardCharsets.UTF_8)))
                        .param("sourceType", "INTERNAL_LEDGER")
                        .with(as(admin))
                        .with(org(org)))
                .andReturn()
                .getResponse();
        batch = UUID.fromString(
                jsonMapper.readTree(res.getContentAsString()).get("id").asString());
        jobRunner.runAllQueued(List.of(org));
        lists.add("/api/v1/imports");
        lists.add("/api/v1/imports/" + batch + "/errors");
        lists.add("/api/v1/transactions?transactionDateFrom=2026-10-01&transactionDateTo=2026-10-31");
        lists.add("/api/v1/reconciliation-runs");
        lists.add("/api/v1/exceptions");
        lists.add("/api/v1/audit-events?entityType=IMPORT_BATCH&entityId=" + batch);
        lists.add("/api/v1/members");
        lists.add("/api/v1/platform/organizations");
    }

    @TestFactory
    Stream<DynamicTest> sizeIsClampedTo200() {
        return lists.stream()
                .map(path -> DynamicTest.dynamicTest(path, () -> {
                    JsonNode body = getJson(path + sep(path) + "size=1000", 200);
                    assertThat(body.get("page").get("size").asInt())
                            .as("effective size")
                            .isEqualTo(200);
                    assertThat(body.get("content").size()).isLessThanOrEqualTo(200);
                }));
    }

    @TestFactory
    Stream<DynamicTest> anUnknownSortFieldIs400() {
        return lists.stream()
                .map(path -> DynamicTest.dynamicTest(path, () -> {
                    JsonNode body = getJson(path + sep(path) + "sort=password_hash,asc", 400);
                    assertThat(body.get("code").asString()).isEqualTo("validation-failed");
                    assertThat(body.toString())
                            .contains("unsupported-sort-field")
                            .doesNotContain("password_hash\"");
                }));
    }

    @TestFactory
    Stream<DynamicTest> theRunMatchesListIsBoundedToo() {
        return Stream.of(DynamicTest.dynamicTest("/reconciliation-runs/{id}/matches", () -> {
            UUID realRun = queueAndRunEmptyRun();
            String path = "/api/v1/reconciliation-runs/" + realRun + "/matches";
            assertThat(getJson(path + "?size=1000", 200).get("page").get("size").asInt())
                    .isEqualTo(200);
            assertThat(getJson(path + "?sort=id,asc", 400).get("code").asString())
                    .isEqualTo("validation-failed");
        }));
    }

    @Test
    void everyListEndpointIsCovered() {
        // GET collection endpoints in the code (RequestMappingHandlerMapping would also work; this
        // list is short, so a hand-kept one with a reminder is clearer). Detail endpoints aren't lists.
        assertThat(lists.stream().map(p -> p.replaceAll("\\?.*", "").replace(batch.toString(), "{id}")))
                .containsExactlyInAnyOrder(
                        "/api/v1/imports",
                        "/api/v1/imports/{id}/errors",
                        "/api/v1/transactions",
                        "/api/v1/reconciliation-runs",
                        "/api/v1/exceptions",
                        "/api/v1/audit-events",
                        "/api/v1/members",
                        "/api/v1/platform/organizations");
    }

    private UUID queueAndRunEmptyRun() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/reconciliation-runs")
                        .with(as(admin))
                        .with(org(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2026-10-03\",\"currency\":\"AED\"}"))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isIn(200, 202);
        jobRunner.runAllQueued(List.of(org));
        return UUID.fromString(
                jsonMapper.readTree(res.getContentAsString()).get("id").asString());
    }

    private JsonNode getJson(String path, int status) throws Exception {
        boolean platform = path.startsWith("/api/v1/platform");
        var req = get(path).with(as(platform ? platformAdmin : admin));
        if (!platform) {
            req = req.with(org(org));
        }
        MockHttpServletResponse res = mockMvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(path + ": " + res.getContentAsString()).isEqualTo(status);
        return jsonMapper.readTree(res.getContentAsString());
    }

    private static String sep(String path) {
        return path.contains("?") ? "&" : "?";
    }
}
