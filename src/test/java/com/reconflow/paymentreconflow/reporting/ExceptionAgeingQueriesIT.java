package com.reconflow.paymentreconflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingQueries;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingQueries.Filter;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingQueries.Row;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M8.3: which bucket a case lands in, on every boundary. Ages are measured against {@code now()},
 * the transaction's start, so the fixture inserts its cases and runs the query in <em>one</em>
 * transaction: {@code created_at = now() - interval 'X'} is then exactly X old for the query, with
 * no clock to fake and nothing to sleep for. (Rolled back at the end: nothing is left behind.)
 */
class ExceptionAgeingQueriesIT extends AbstractIntegrationTest {

    @Autowired
    ExceptionAgeingQueries queries;

    @Autowired
    TransactionTemplate tx;

    @Test
    void everyBoundaryLandsInTheUpperBucket() {
        var f = new ReportFixture(jdbc);
        // One application transaction, bound to the tenant (RLS, M10.7), so every case's age is
        // measured from the same now() and the inserts roll back with it.
        Map<Integer, Long> byBucket = asTenant(
                f.org,
                () -> tx.execute(s -> {
                    UUID run = run(f);
                    // Lower bounds are inclusive: exactly 1 day old is 1-3d, exactly 30 days is 30d+.
                    String[] ages = {
                        "0 seconds", // 0
                        "1 day - 1 second", // 0
                        "1 day", // 1
                        "3 days - 1 second", // 1
                        "3 days", // 2
                        "6 days 23:59:59", // 2
                        "7 days", // 3
                        "30 days - 1 second", // 3
                        "30 days", // 4
                        "400 days" // 4
                    };
                    for (String age : ages) {
                        insertCase(f, run, "OPEN", age);
                    }
                    insertCase(f, run, "RESOLVED", "2 days"); // not active: not in the report
                    List<Row> rows = queries.ageing(f.org, new Filter(List.of(), null, false, null));
                    Map<Integer, Long> counts = new TreeMap<>();
                    rows.stream()
                            .filter(r -> r.level() == 3)
                            .forEach(r -> counts.merge(r.bucket(), r.cases(), Long::sum));
                    s.setRollbackOnly();
                    return counts;
                }));

        assertThat(byBucket).isEqualTo(Map.of(0, 2L, 1, 2L, 2, 2L, 3, 2L, 4, 2L));
    }

    private UUID run(ReportFixture f) {
        UUID run = Ids.newId();
        appJdbc.update("""
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, status, triggered_by)
                VALUES (?, ?, ?, ?, DATE '2026-10-01', DATE '2026-10-31', 'AED', 'COMPLETED', ?)""", run, f.org, "client:" + run, "0".repeat(64), f.admin.id());
        return run;
    }

    private void insertCase(ReportFixture f, UUID run, String status, String age) {
        UUID id = Ids.newId();
        boolean resolved = status.equals("RESOLVED");
        appJdbc.update(
                """
                INSERT INTO exception_case (id, organization_id, run_id, type, discrepancy_key, status, currency, amount,
                       resolution_code, resolved_by, resolved_at, created_at)
                VALUES (?, ?, ?, 'MISSING_IN_GATEWAY', ?, ?, 'AED', 10, ?, ?, CASE WHEN ? THEN now() END,
                        now() - ?::interval)""",
                id,
                f.org,
                run,
                "MISSING_IN_GATEWAY:PAY-" + id + ":PAYMENT:AED",
                status,
                resolved ? "OTHER" : null,
                resolved ? f.investigator.id() : null,
                resolved,
                age);
    }
}
