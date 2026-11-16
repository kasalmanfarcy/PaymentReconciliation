package com.reconflow.paymentreconflow.reporting;

import static com.reconflow.paymentreconflow.reporting.ReconciliationReportIT.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M8.3: {@code GET /api/v1/reports/exception-ageing}. Five buckets always, zeros included; an
 * "unassigned" row first; amounts per currency; the type, assignee and currency filters.
 */
class ExceptionAgeingReportIT extends AbstractIntegrationTest {

    static final String REPORT = "/api/v1/reports/exception-ageing";

    @Test
    void bucketsByAgeAndAssigneeWithTheUnassignedRowFirst() throws Exception {
        var f = new ReportFixture(jdbc);
        UUID run = run(f);
        insertCase(f, run, "AMOUNT_MISMATCH", "OPEN", null, "AED", "100.0000", "2 hours");
        insertCase(f, run, "MISSING_IN_LEDGER", "OPEN", null, "USD", "7.5000", "2 days");
        insertCase(f, run, "MISSING_IN_GATEWAY", "IN_REVIEW", f.investigator.id(), "AED", "40.0000", "10 days");
        insertCase(f, run, "MISSING_IN_GATEWAY", "OPEN", f.investigator.id(), "AED", "60.0000", "12 days");
        insertCase(f, run, "MISSING_IN_GATEWAY", "RESOLVED", f.investigator.id(), "AED", "999.0000", "12 days");

        Map<String, Object> report = report(f, "");

        assertThat(list(report, "buckets"))
                .extracting(b -> b.get("bucket") + " " + b.get("cases") + " " + b.get("amountAtStake"))
                .containsExactly(
                        "0-1d 1 [{currency=AED, amount=100.0000}]",
                        "1-3d 1 [{currency=USD, amount=7.5000}]",
                        "3-7d 0 []",
                        "7-30d 2 [{currency=AED, amount=100.0000}]",
                        "30d+ 0 []");
        List<Map<String, Object>> assignees = list(report, "byAssignee");
        assertThat(assignees).hasSize(2);
        assertThat(assignees.get(0)).containsEntry("assignee", null).containsEntry("cases", 2);
        assertThat(assignees.get(1))
                .containsEntry("cases", 2)
                .extractingByKey("assignee")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("id", f.investigator.id().toString());
        assertThat(list(assignees.get(1), "buckets"))
                .extracting(b -> b.get("bucket") + " " + b.get("cases"))
                .containsExactly("0-1d 0", "1-3d 0", "3-7d 0", "7-30d 2", "30d+ 0");
        assertThat(report.get("asOf")).isNotNull();
    }

    @Test
    void filtersNarrowTheCases() throws Exception {
        var f = new ReportFixture(jdbc);
        UUID run = run(f);
        insertCase(f, run, "AMOUNT_MISMATCH", "OPEN", null, "AED", "100.0000", "2 hours");
        insertCase(f, run, "MISSING_IN_LEDGER", "OPEN", null, "USD", "7.5000", "2 days");
        insertCase(f, run, "MISSING_IN_GATEWAY", "OPEN", f.investigator.id(), "AED", "60.0000", "12 days");

        assertThat(total(report(f, "?type=AMOUNT_MISMATCH&type=MISSING_IN_LEDGER")))
                .isEqualTo(2);
        assertThat(total(report(f, "?assignedTo=unassigned"))).isEqualTo(2);
        assertThat(total(report(f, "?assignedTo=" + f.investigator.id()))).isEqualTo(1);
        assertThat(total(report(f, "?currency=usd"))).isEqualTo(1);
        assertThat(total(report(f, "?assignedTo=" + UUID.randomUUID()))).isZero();
        // "me" is the caller: the investigator sees the one case assigned to them.
        MockHttpServletResponse mine = mockMvc.perform(get(REPORT + "?assignedTo=me")
                        .with(as(f.investigator.id()))
                        .with(org(f.org)))
                .andReturn()
                .getResponse();
        assertThat(total(jsonMapper.readValue(mine.getContentAsString(), Map.class)))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?type=DUPLICATE", "?assignedTo=someone", "?currency=XX"})
    void badFiltersAre400(String query) throws Exception {
        var f = new ReportFixture(jdbc);
        MockHttpServletResponse res = mockMvc.perform(
                        get(REPORT + query).with(as(f.viewer.id())).with(org(f.org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString()).contains("\"code\":\"validation-failed\"");
    }

    @SuppressWarnings("unchecked")
    private static long total(Map<?, ?> report) {
        return ((List<Map<String, Object>>) report.get("buckets"))
                .stream().mapToLong(b -> ((Number) b.get("cases")).longValue()).sum();
    }

    private UUID run(ReportFixture f) {
        UUID run = Ids.newId();
        jdbc.update("""
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, status, triggered_by)
                VALUES (?, ?, ?, ?, DATE '2026-10-01', DATE '2026-10-31', 'AED', 'COMPLETED', ?)""", run, f.org, "client:" + run, "0".repeat(64), f.admin.id());
        return run;
    }

    private void insertCase(
            ReportFixture f,
            UUID run,
            String type,
            String status,
            UUID assignee,
            String currency,
            String amount,
            String age) {
        UUID id = Ids.newId();
        boolean resolved = status.equals("RESOLVED");
        jdbc.update(
                """
                INSERT INTO exception_case (id, organization_id, run_id, type, discrepancy_key, status, currency, amount,
                       assigned_to, resolution_code, resolved_by, resolved_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::numeric, ?, ?, ?, CASE WHEN ? THEN now() END, now() - ?::interval)""",
                id,
                f.org,
                run,
                type,
                type + ":PAY-" + id + ":PAYMENT:" + currency,
                status,
                currency,
                amount,
                assignee,
                resolved ? "OTHER" : null,
                resolved ? f.investigator.id() : null,
                resolved,
                age);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> report(ReportFixture f, String query) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(
                        get(REPORT + query).with(as(f.viewer.id())).with(org(f.org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return jsonMapper.readValue(res.getContentAsString(), Map.class);
    }
}
