package com.reconflow.paymentreconflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingQueries;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingQueries.Filter;
import com.reconflow.paymentreconflow.reporting.export.ExceptionCsvExporter;
import com.reconflow.paymentreconflow.reporting.export.ExportQueries;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityQueries;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReportQueries;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.TestData;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * M8.5: every report statement, EXPLAIN (ANALYZE, BUFFERS)ed against realistic volume. Plans on a
 * near-empty table are meaningless (the planner rightly prefers a sequential scan), so this loads a
 * target organization with 60,000 transaction records, 15,000 cases over 30 overlapping runs, 40
 * batches with 10,000 row errors, and seven neighbours with the same cases, runs and errors: a
 * tenant's report reads a slice of shared tables. Then ANALYZE, then the plans.
 *
 * <p>The statements are the production strings themselves ({@code STATEMENTS} on each query class),
 * bound with real parameters, so this can't check a stale copy. What is asserted is that no
 * statement scans a large table whole; the plans and timings are logged, and summarised in
 * docs/benchmarks.md.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // load once, not per statement
class ReportQueryPlanIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ReportQueryPlanIT.class);

    static final LocalDate OCT = LocalDate.of(2026, 10, 1);
    static final int RUNS = 30;

    /** Large tables: a "Seq Scan on" one of these is a failed plan. Small ones (runs, users, batches) may be scanned. */
    static final List<String> LARGE = List.of(
            "exception_case ",
            "exception_case e",
            "exception_case_run",
            "exception_case_record",
            "import_row_error",
            "transaction_record");

    UUID org;
    UUID investigator;
    UUID[] runs;
    UUID errorBatch;

    @BeforeAll
    void load() {
        for (int i = 0; i < 7; i++) {
            UUID neighbour = data().newOrganization();
            loadOrganization(
                    neighbour, data().newMember(neighbour, Role.INVESTIGATOR).id());
        }
        org = data().newOrganization();
        investigator = data().newMember(org, Role.INVESTIGATOR).id();
        loadOrganization(org, investigator);
        loadRecords(org, investigator);
        for (String table : List.of(
                "exception_case",
                "exception_case_run",
                "exception_case_record",
                "reconciliation_run",
                "reconciliation_run_day",
                "import_batch",
                "import_row_error",
                "transaction_record",
                "app_user")) {
            jdbc.execute("ANALYZE " + table);
        }
    }

    /** Runs with day rows, cases linked to their runs and days, batches with errors. */
    private void loadOrganization(UUID organization, UUID user) {
        TestData data = new TestData(jdbc);
        // 30 runs of 5 days each, starting a day apart: heavy overlap, as re-runs produce.
        UUID[] orgRuns = new UUID[RUNS];
        for (int i = 0; i < RUNS; i++) {
            orgRuns[i] = Ids.newId();
            LocalDate start = OCT.plusDays(i % 27);
            jdbc.update(
                    """
                    INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                           period_start, period_end, currency, status, triggered_by, completed_at,
                           matched_count, matched_amount)
                    VALUES (?, ?, ?, ?, ?, ?, 'AED', 'COMPLETED', ?, now() - (? || ' minutes')::interval, 9000, 900000)""",
                    orgRuns[i],
                    organization,
                    "client:" + orgRuns[i],
                    "0".repeat(64),
                    start,
                    start.plusDays(4),
                    user,
                    RUNS - i);
            jdbc.update("""
                    INSERT INTO reconciliation_run_day (run_id, organization_id, day, currency, matched_count,
                           matched_amount, mismatch_count, mismatch_net_difference, unmatched_left_count,
                           unmatched_left_amount, unmatched_right_count, unmatched_right_amount, duplicate_count,
                           currency_mismatch_count)
                    SELECT ?, ?, d::date, 'AED', 1800, 180000.0000, 10, -50.0000, 15, 1500.0000, 12, 1200.0000, 3, 0
                      FROM generate_series(?::date, ?::date, interval '1 day') AS d""", orgRuns[i], organization, start, start.plusDays(4));
        }
        // 15,000 cases: ~70% resolved, a third of the rest assigned, ages up to 60 days.
        jdbc.update("""
                INSERT INTO exception_case (id, organization_id, run_id, type, reason_code, discrepancy_key, status,
                       currency, amount, amount_difference, assigned_to, resolution_code, resolved_by, resolved_at,
                       created_at)
                SELECT uuid_generate_v7(), ?, (?::uuid[])[1 + g % 30],
                       (ARRAY['MISSING_IN_GATEWAY','MISSING_IN_LEDGER','AMOUNT_MISMATCH','DUPLICATE_IN_SOURCE'])[1 + g % 4],
                       CASE WHEN g % 4 = 3 THEN 'SAME_AMOUNT' END,
                       'K' || g || ':PAY-' || g || ':PAYMENT:AED',
                       CASE WHEN g % 10 < 7 THEN 'RESOLVED' WHEN g % 10 < 9 THEN 'OPEN' ELSE 'IN_REVIEW' END,
                       'AED', 10 + g % 1000, CASE WHEN g % 4 = 2 THEN -(g % 50) END,
                       CASE WHEN g % 10 >= 7 AND g % 3 = 0 THEN ?::uuid END,
                       CASE WHEN g % 10 < 7 THEN 'TIMING_DIFFERENCE' END,
                       CASE WHEN g % 10 < 7 THEN ?::uuid END,
                       CASE WHEN g % 10 < 7 THEN now() END,
                       now() - ((g % 60) || ' days')::interval
                  FROM generate_series(1, 15000) AS g
                """, organization, orgRuns, user, user);
        jdbc.update("""
                INSERT INTO exception_case_run (exception_id, run_id, organization_id, created, day)
                SELECT c.id, c.run_id, c.organization_id, true, r.period_start + (abs(hashtext(c.id::text)) % 5)
                  FROM exception_case c JOIN reconciliation_run r ON r.id = c.run_id
                 WHERE c.organization_id = ?
                """, organization);
        // 40 batches through October, 250 row errors each (some rows with two bad columns).
        for (int b = 0; b < 40; b++) {
            UUID batch = data.newImportBatch(
                    organization, user, b % 2 == 0 ? "INTERNAL_LEDGER" : "GATEWAY", "COMPLETED_WITH_ERRORS");
            jdbc.update(
                    "UPDATE import_batch SET created_at = ?, total_rows = 5000, valid_rows = 4800, error_rows = 200"
                            + " WHERE id = ?",
                    OffsetDateTime.of(OCT.atStartOfDay(), ZoneOffset.UTC).plusHours(18L * b),
                    batch);
            jdbc.update("""
                    INSERT INTO import_row_error (id, organization_id, batch_id, row_number, column_name, error_code,
                           message)
                    SELECT uuid_generate_v7(), ?, ?, 1 + g / 5 * 4 + g % 4,
                           (ARRAY['amount','currency','status','transaction_date','reference'])[1 + g % 5],
                           (ARRAY['INVALID_AMOUNT','INVALID_CURRENCY','UNKNOWN_STATUS','INVALID_DATE','MISSING_REQUIRED_FIELD'])[1 + g % 5],
                           'bad'
                      FROM generate_series(0, 249) AS g
                    """, organization, batch);
            if (organization.equals(org)) {
                errorBatch = batch;
            }
        }
        if (organization.equals(org)) {
            runs = orgRuns;
        }
    }

    /** The target organization's 60,000 records, and one ledger record linked to each case (for the export). */
    private void loadRecords(UUID organization, UUID user) {
        TestData data = new TestData(jdbc);
        for (String source : List.of("INTERNAL_LEDGER", "GATEWAY")) {
            UUID batch = data.newImportBatch(organization, user, source, "COMPLETED");
            jdbc.update("""
                    INSERT INTO transaction_record (id, organization_id, batch_id, source_type, source_row_number,
                           raw_reference, normalized_reference, amount, currency, status, transaction_date, raw_values)
                    SELECT uuid_generate_v7(), ?, ?, ?, g, 'PAY-' || g, 'PAY-' || g, 10 + g % 1000, 'AED', 'SUCCESS',
                           DATE '2026-10-01' + g % 31, '{}'::jsonb
                      FROM generate_series(1, 30000) AS g
                    """, organization, batch, source);
        }
        jdbc.update("""
                INSERT INTO exception_case_record (exception_id, transaction_record_id, transaction_date, organization_id)
                SELECT c.id, t.id, t.transaction_date, c.organization_id
                  FROM (SELECT id, organization_id, row_number() OVER (ORDER BY id) AS rn
                          FROM exception_case WHERE organization_id = ?) c
                  JOIN (SELECT id, transaction_date, row_number() OVER (ORDER BY id) AS rn
                          FROM transaction_record WHERE organization_id = ? AND source_type = 'INTERNAL_LEDGER') t
                    ON t.rn = c.rn
                """, organization, organization);
    }

    /** Every statement with the parameters a real request would bind. */
    private Map<String, Map.Entry<String, Map<String, Object>>> statements() {
        UUID run = runs[7];
        Map<String, Object> runParams = Map.of("orgId", org, "runId", run);
        Map<String, Object> range = Map.of("orgId", org, "from", OCT, "to", OCT.plusDays(30), "currency", "AED");
        Map<String, Object> overTime = Map.of(
                "orgId",
                org,
                "granularity",
                "day",
                "fromInclusive",
                OffsetDateTime.of(OCT.atStartOfDay(), ZoneOffset.UTC),
                "toExclusive",
                OffsetDateTime.of(OCT.plusDays(31).atStartOfDay(), ZoneOffset.UTC),
                "sourceType",
                "GATEWAY");
        Map<String, Map.Entry<String, Map<String, Object>>> all = new LinkedHashMap<>();
        ReconciliationReportQueries.STATEMENTS.forEach((name, sql) ->
                all.put(name, Map.entry(sql, name.startsWith("reconciliation.run") ? runParams : range)));
        ImportQualityQueries.STATEMENTS.forEach((name, sql) -> all.put(
                name,
                Map.entry(
                        sql,
                        name.startsWith("importQuality.batch")
                                ? Map.of("orgId", org, "batchId", errorBatch)
                                : overTime)));
        all.put(
                "ageing",
                Map.entry(ExceptionAgeingQueries.sql(new Filter(List.of(), null, false, null)), Map.of("orgId", org)));
        all.put(
                "ageing.assignee",
                Map.entry(
                        ExceptionAgeingQueries.sql(new Filter(List.of(), investigator, false, null)),
                        Map.of("orgId", org, "assignee", investigator)));
        all.put(
                "ageing.unassigned",
                Map.entry(ExceptionAgeingQueries.sql(new Filter(List.of(), null, true, null)), Map.of("orgId", org)));
        all.put("export.count", Map.entry(ExportQueries.COUNT_FOR_RUN, runParams));
        all.put("export.rows", Map.entry(ExceptionCsvExporter.EXPORT, runParams));
        return all;
    }

    @TestFactory
    Stream<DynamicTest> noReportStatementScansALargeTable() {
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbc);
        return statements().entrySet().stream()
                .map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
                    String sql = e.getValue().getKey();
                    MapSqlParameterSource params =
                            new MapSqlParameterSource(e.getValue().getValue());
                    // Twice: the first run warms the cache; the second is the steady state that is recorded.
                    named.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, params, String.class);
                    String plan = String.join(
                            "\n", named.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, params, String.class));
                    log.info("Plan for {}:\n{}", e.getKey(), plan);
                    log.info("PLAN-SUMMARY | {} | {} | {}", e.getKey(), executionTime(plan), scans(plan));
                    // An empty partition the executor pruned at run time shows "(never executed)": fine.
                    String executed = plan.lines()
                            .filter(l -> !l.contains("(never executed)"))
                            .collect(Collectors.joining("\n"));
                    for (String table : LARGE) {
                        assertThat(executed).as(e.getKey()).doesNotContain("Seq Scan on " + table);
                    }
                }));
    }

    private static String executionTime(String plan) {
        Matcher m = Pattern.compile("Execution Time: ([0-9.]+ ms)").matcher(plan);
        return m.find() ? m.group(1) : "?";
    }

    /** The scan nodes, e.g. "Index Scan using exception_case_run_pkey on exception_case_run cr". */
    private static String scans(String plan) {
        return plan.lines()
                .map(String::strip)
                .map(l -> l.startsWith("->") ? l.substring(2).strip() : l)
                .filter(l -> l.matches("^(Parallel )?(Seq|Index|Index Only|Bitmap Heap|Bitmap Index) Scan.*"))
                .map(l -> l.replaceAll("\\s+\\(cost.*", "").replaceAll("\\s+\\(actual.*", ""))
                .distinct()
                .collect(Collectors.joining("; "));
    }
}
