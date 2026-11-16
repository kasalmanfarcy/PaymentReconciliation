package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M7.6: the work-queue shapes against realistic volume. Plans on a near-empty table are meaningless
 * (the planner prefers a sequential scan), so this loads 30,000 cases for one organization (about
 * 70% resolved, a third of the rest assigned, spread over 30 runs) next to whatever other tests
 * left, beside three other organizations of the same size, and ANALYZEs first. The plans are printed and summarised in docs/database-design.md §3.5.
 *
 * <p>Only the queries' shape is copied here (ExceptionList builds them); what is asserted is that no
 * shape scans the whole table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // load the 120,000 rows once, not per test
class ExceptionListQueryPlanIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ExceptionListQueryPlanIT.class);

    UUID org;
    UUID investigator;
    UUID run;

    @BeforeAll
    void load() {
        // The target organization plus three neighbours of the same size: a tenant's queue is a slice
        // of a shared table, and with one tenant alone the planner rightly prefers a sequential scan.
        for (int i = 0; i < 3; i++) {
            UUID neighbour = data().newOrganization();
            loadCases(neighbour, data().newMember(neighbour, Role.INVESTIGATOR).id());
        }
        org = data().newOrganization();
        investigator = data().newMember(org, Role.INVESTIGATOR).id();
        run = loadCases(org, investigator);
        jdbc.execute("ANALYZE exception_case");
        jdbc.execute("ANALYZE exception_case_run");
    }

    /** 30,000 cases over 30 runs; returns one of the runs. */
    private UUID loadCases(UUID organization, UUID user) {
        UUID[] runs = new UUID[30];
        for (int i = 0; i < runs.length; i++) {
            runs[i] = Ids.newId();
            jdbc.update("""
                    INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                           period_start, period_end, currency, status, triggered_by)
                    VALUES (?, ?, ?, ?, DATE '2026-10-01', DATE '2026-10-31', 'AED', 'COMPLETED', ?)""", runs[i], organization, "client:" + runs[i], "0".repeat(64), user);
        }
        jdbc.update("""
                INSERT INTO exception_case (id, organization_id, run_id, type, discrepancy_key, status, currency, amount,
                       assigned_to, resolution_code, resolved_by, resolved_at, created_at)
                SELECT uuid_generate_v7(), ?, (?::uuid[])[1 + g % 30], 'MISSING_IN_GATEWAY',
                       'MISSING_IN_GATEWAY:PAY-' || g || ':PAYMENT:AED',
                       CASE WHEN g % 10 < 7 THEN 'RESOLVED' WHEN g % 10 < 9 THEN 'OPEN' ELSE 'IN_REVIEW' END,
                       'AED', g % 1000,
                       CASE WHEN g % 10 >= 7 AND g % 3 = 0 THEN ?::uuid END,
                       CASE WHEN g % 10 < 7 THEN 'TIMING_DIFFERENCE' END,
                       CASE WHEN g % 10 < 7 THEN ?::uuid END,
                       CASE WHEN g % 10 < 7 THEN now() END,
                       now() - (g || ' minutes')::interval
                  FROM generate_series(1, 30000) AS g
                """, organization, runs, user, user);
        jdbc.update("""
                INSERT INTO exception_case_run (exception_id, run_id, organization_id, created)
                SELECT id, run_id, organization_id, true FROM exception_case WHERE organization_id = ?
                """, organization);
        return runs[7];
    }

    private static final String LIST = """
            SELECT e.id FROM exception_case e
             WHERE e.organization_id = '%s' AND e.status IN ('OPEN', 'IN_REVIEW') %s
             ORDER BY e.created_at DESC, e.id DESC LIMIT 50
            """;

    @Test
    void theDefaultQueueUsesTheListIndex() {
        String plan = explain(LIST.formatted(org, ""));

        assertThat(plan).contains("exception_case_list_idx").doesNotContain("Seq Scan on exception_case");
    }

    @Test
    void myExceptionsUseTheAssigneeIndex() {
        String plan = explain(LIST.formatted(org, "AND e.assigned_to = '" + investigator + "'"));

        assertThat(plan)
                .containsPattern("exception_case_(assignee|list)_idx")
                .doesNotContain("Seq Scan on exception_case");
    }

    @Test
    void unassignedExceptionsNeedNoExtraIndex() {
        // The open queue is a small part of the table and mostly unassigned, so the list index plus a
        // filter on assigned_to is enough: no partial "WHERE assigned_to IS NULL" index needed.
        String plan = explain(LIST.formatted(org, "AND e.assigned_to IS NULL"));

        assertThat(plan).contains("exception_case_list_idx").doesNotContain("Seq Scan on exception_case");
    }

    @Test
    void aRunFilterUsesTheRunLinkKey() {
        String plan = explain(LIST.formatted(
                org,
                "AND EXISTS (SELECT 1 FROM exception_case_run r WHERE r.exception_id = e.id"
                        + " AND r.organization_id = e.organization_id AND r.run_id = '" + run + "')"));

        assertThat(plan).doesNotContain("Seq Scan on exception_case ");
        // Any key leading with run_id (or the per-case one): M8's V8 added exception_case_run_day_idx
        // (run_id, day), which the planner may prefer for the same run_id lookup.
        assertThat(plan)
                .containsPattern("exception_case_run_pkey|exception_case_run_exception_idx|exception_case_run_day_idx");
    }

    private String explain(String sql) {
        List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + sql, String.class);
        String text = String.join("\n", plan);
        log.info("Plan:\n{}", text);
        return text;
    }
}
