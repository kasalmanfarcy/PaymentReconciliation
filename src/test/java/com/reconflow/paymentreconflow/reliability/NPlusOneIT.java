package com.reconflow.paymentreconflow.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.QueryCounter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/**
 * M9.7: no endpoint's query count grows with the data it returns. An N+1 (one query for the page,
 * then one more per row to load a relation) is invisible with two rows and slow with two thousand,
 * so every list and detail endpoint is called for a small organization (20 references) and a large
 * one (200), on full pages, and must run exactly the same number of statements, below a small
 * bound. Statements are counted by PostgreSQL itself ({@link QueryCounter}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NPlusOneIT extends AbstractIntegrationTest {

    /** A page, its count, the tenant's set_config, a join or two: anything above this is suspicious. */
    static final long MAX_STATEMENTS = 15;

    static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);

    @Autowired
    JobRunner jobRunner;

    record Tenant(UUID org, UUID admin, UUID ledgerBatch, UUID run, UUID duplicateCase) {}

    Tenant small;
    Tenant large;

    @BeforeAll
    void twoOrganizationsOfDifferentSizes() throws Exception {
        small = pipeline(20, 3);
        large = pipeline(200, 15);
    }

    @TestFactory
    Stream<DynamicTest> queryCountsDoNotGrowWithTheData() {
        Map<String, Function<Tenant, String>> endpoints = new LinkedHashMap<>();
        endpoints.put("GET /imports", t -> "/api/v1/imports?size=200");
        endpoints.put("GET /imports/{id}", t -> "/api/v1/imports/" + t.ledgerBatch());
        endpoints.put("GET /imports/{id}/errors", t -> "/api/v1/imports/" + t.ledgerBatch() + "/errors?size=200");
        endpoints.put(
                "GET /transactions",
                t -> "/api/v1/transactions?size=200&transactionDateFrom=2026-10-01&transactionDateTo=2026-10-31");
        endpoints.put("GET /reconciliation-runs", t -> "/api/v1/reconciliation-runs?size=200");
        endpoints.put("GET /reconciliation-runs/{id}", t -> "/api/v1/reconciliation-runs/" + t.run());
        endpoints.put(
                "GET /reconciliation-runs/{id}/matches",
                t -> "/api/v1/reconciliation-runs/" + t.run() + "/matches?size=200");
        endpoints.put("GET /exceptions", t -> "/api/v1/exceptions?size=200");
        endpoints.put("GET /exceptions/{id}", t -> "/api/v1/exceptions/" + t.duplicateCase());
        endpoints.put(
                "GET /audit-events", t -> "/api/v1/audit-events?entityType=RECONCILIATION_RUN&entityId=" + t.run());
        endpoints.put("GET /members", t -> "/api/v1/members?size=200");
        endpoints.put("GET /reports/reconciliation", t -> "/api/v1/reports/reconciliation?runId=" + t.run());
        endpoints.put("GET /reports/import-quality", t -> "/api/v1/reports/import-quality?batchId=" + t.ledgerBatch());
        endpoints.put("GET /reports/exception-ageing", t -> "/api/v1/reports/exception-ageing");

        List<DynamicTest> tests = new ArrayList<>();
        endpoints.forEach((name, path) -> tests.add(DynamicTest.dynamicTest(name, () -> {
            long few = statements(small, path.apply(small));
            long many = statements(large, path.apply(large));
            System.out.printf("N+1 check %-40s %d statements (20 refs), %d (200 refs)%n", name, few, many);
            assertThat(many)
                    .as("%s: %d statements for 20 references, %d for 200", name, few, many)
                    .isEqualTo(few)
                    .isLessThanOrEqualTo(MAX_STATEMENTS);
        })));
        return tests.stream();
    }

    private long statements(Tenant t, String path) throws Exception {
        QueryCounter counter = new QueryCounter(jdbc);
        counter.reset();
        MockHttpServletResponse res = mockMvc.perform(
                        get(path).with(as(t.admin())).with(org(t.org())))
                .andReturn()
                .getResponse();
        long n = counter.statements();
        assertThat(res.getStatus()).as(path + ": " + res.getContentAsString()).isEqualTo(200);
        return n;
    }

    // ---------------------------------------------------------------------------------------------

    private Tenant pipeline(int references, int members) throws Exception {
        UUID org = data().newOrganization();
        UUID admin = data().newMember(org, Role.ORG_ADMIN).id();
        for (int i = 1; i < members; i++) {
            data().newMember(org, Role.INVESTIGATOR);
        }
        UUID ledger = upload(org, admin, ledgerCsv(references), "INTERNAL_LEDGER");
        upload(org, admin, gatewayCsv(references), "GATEWAY");
        jobRunner.runAllQueued(List.of(org));
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/reconciliation-runs")
                        .with(as(admin))
                        .with(org(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodStart\":\"" + DAY_1 + "\",\"periodEnd\":\"" + DAY_1.plusDays(3)
                                + "\",\"currency\":\"AED\"}"))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).isEqualTo(202);
        jobRunner.runAllQueued(List.of(org));
        UUID run = jdbc.queryForObject("SELECT id FROM reconciliation_run WHERE organization_id = ?", UUID.class, org);
        UUID duplicate = jdbc.queryForObject(
                "SELECT id FROM exception_case WHERE organization_id = ? AND type = 'DUPLICATE_IN_SOURCE' LIMIT 1",
                UUID.class,
                org);
        return new Tenant(org, admin, ledger, run, duplicate);
    }

    private UUID upload(UUID org, UUID user, String csv, String source) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile(
                                "file", source + ".csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("sourceType", source)
                        .with(as(user))
                        .with(org(org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        return UUID.fromString(
                jsonMapper.readTree(res.getContentAsString()).get("id").asString());
    }

    /** Every 10th reference ledger-only, every 10th (+2) mismatched, (+3) doubled in the gateway; every 7th a bad row. */
    private static String ledgerCsv(int n) {
        StringBuilder csv = new StringBuilder("reference,amount,currency,status,transaction_at\n");
        for (int i = 0; i < n; i++) {
            csv.append("NP-")
                    .append(i)
                    .append(',')
                    .append(amount(i))
                    .append(",AED,SUCCESS,")
                    .append(at(i))
                    .append('\n');
            if (i % 7 == 0) {
                csv.append("BAD-")
                        .append(i)
                        .append(",abc,AED,SUCCESS,")
                        .append(at(i))
                        .append('\n');
            }
        }
        return csv.toString();
    }

    private static String gatewayCsv(int n) {
        StringBuilder csv = new StringBuilder("merchant_reference,amount,currency,status,transaction_at\n");
        for (int i = 0; i < n; i++) {
            BigDecimal a = i % 10 == 2 ? amount(i).add(BigDecimal.ONE) : amount(i);
            int copies = i % 10 == 0 ? 0 : i % 10 == 3 ? 2 : 1;
            for (int c = 0; c < copies; c++) {
                csv.append("NP-")
                        .append(i)
                        .append(',')
                        .append(a)
                        .append(",aed,captured,")
                        .append(at(i))
                        .append('\n');
            }
        }
        return csv.toString();
    }

    private static BigDecimal amount(int i) {
        return new BigDecimal(100 + i).add(new BigDecimal("0.50"));
    }

    private static String at(int i) {
        return DAY_1.plusDays(i % 4) + "T09:00:00Z";
    }
}
