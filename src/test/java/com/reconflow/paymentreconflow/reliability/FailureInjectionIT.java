package com.reconflow.paymentreconflow.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.fault.FaultPoints;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.ScriptedFaultInjector;
import com.reconflow.paymentreconflow.support.ScriptedFaultInjector.SimulatedCrash;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * M9.4: crash the work at every named point, recover the way production does, and require a final
 * state <em>identical</em> to a run that never crashed. This is the evidence for "at-least-once
 * execution, exactly-once effects" (phase-M9 §2.1): checkpoints make a resumed job skip finished
 * work, and unique constraints reject anything repeated.
 *
 * <p>The scenario is the whole pipeline: upload a ledger and a gateway file (three 100-row chunks
 * each in the test profile, with malformed rows, duplicates, mismatches and missing records over
 * four days), import both, request a run, reconcile. Each point is crashed on its first, second and
 * last hit. A crash is {@link SimulatedCrash}, an {@code Error} the queue doesn't catch, so the job
 * stays RUNNING with its lease, exactly like {@code kill -9}; recovery is what production does:
 * the lease expires, the sweeper re-queues, another worker resumes.
 *
 * <p>The retry path (the job runner re-queues after a transient failure) is tested separately from
 * the crash path, and so are object-storage outages.
 */
class FailureInjectionIT extends AbstractIntegrationTest {

    static final LocalDate DAY_1 = LocalDate.of(2026, 10, 1);
    static final LocalDate DAY_4 = DAY_1.plusDays(3);

    @Autowired
    ScriptedFaultInjector faults;

    @Autowired
    JobRunner jobRunner;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        faults.clear();
    }

    /** One organization's run of the scenario. */
    record Tenant(UUID org, UUID user) {}

    @TestFactory
    Stream<DynamicTest> aCrashAtAnyPointThenRecoveryEqualsACleanRun() throws Exception {
        faults.resetHits();
        Snapshot clean = snapshot(runScenario(newTenant()));
        Map<String, Integer> totalHits = new LinkedHashMap<>();
        for (String point : FaultPoints.CRASH_POINTS) {
            totalHits.put(point, faults.hits(point));
        }
        assertThat(totalHits)
                .as("every crash point is on the scenario's path")
                .allSatisfy((p, n) -> assertThat(n).isPositive());

        List<DynamicTest> tests = new ArrayList<>();
        totalHits.forEach((point, last) -> {
            for (int hit : new LinkedHashSet<>(List.of(1, Math.min(2, last), last))) {
                tests.add(DynamicTest.dynamicTest(point + " hit " + hit + " of " + last, () -> {
                    faults.clear();
                    faults.crashAt(point, hit);
                    Tenant t = newTenant();

                    int crashes = runScenarioRecoveringFromCrashes(t);

                    assertThat(crashes).as("the crash happened").isEqualTo(1);
                    assertThat(snapshot(t)).isEqualTo(clean);
                }));
            }
        });
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> aTransientFailureAtAnyPointIsRetriedByTheQueueToTheCleanResult() throws Exception {
        faults.clear();
        Snapshot clean = snapshot(runScenario(newTenant()));
        return FaultPoints.CRASH_POINTS.stream()
                .map(point -> DynamicTest.dynamicTest(point, () -> {
                    faults.clear();
                    // Every time, so the in-process retry (M9.3) gives up and the job runner's backoff is
                    // what recovers: the job goes back to QUEUED with run_after in the future.
                    faults.fromHit(point, 1, () -> {
                        throw new CannotAcquireLockException("simulated lock timeout at " + point);
                    });
                    Tenant t = newTenant();
                    upload(t, "ledger.csv", ledgerCsv(), "INTERNAL_LEDGER");
                    upload(t, "gateway.csv", gatewayCsv(), "GATEWAY");
                    drain(t);
                    if (importsDone(t)) {
                        requestRun(t);
                        drain(t);
                    }
                    assertThat(jdbc.queryForList(
                                    "SELECT status FROM job WHERE organization_id = ? AND last_error LIKE '%simulated%'",
                                    String.class, t.org()))
                            .as("the failing jobs went back to the queue to wait out their backoff; none failed")
                            .isNotEmpty()
                            .containsOnly("QUEUED");

                    faults.clear(); // the cause goes away
                    finishFromHere(t);

                    assertThat(snapshot(t)).isEqualTo(clean);
                    assertThat(jdbc.queryForObject(
                                    "SELECT max(attempts) FROM job WHERE organization_id = ?", Integer.class, t.org()))
                            .isGreaterThan(1);
                }));
    }

    @Test
    void anUploadDuringAStorageOutageIs503AndCreatesNothing() throws Exception {
        Tenant t = newTenant();
        faults.fromHit(FaultPoints.STORAGE_PUT, 1, () -> {
            throw SdkClientException.create("simulated: connection refused");
        });

        MockHttpServletResponse res = uploadResponse(t, "ledger.csv", ledgerCsv(), "INTERNAL_LEDGER");

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(res.getHeader("Retry-After")).isEqualTo("30");
        assertThat(res.getContentAsString())
                .contains("\"code\":\"storage-unavailable\"")
                .doesNotContain("SdkClientException")
                .doesNotContain("connection refused");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM import_batch WHERE organization_id = ?", Integer.class, t.org()))
                .isZero();

        faults.clear(); // the outage ends; the same upload now succeeds
        assertThat(uploadResponse(t, "ledger.csv", ledgerCsv(), "INTERNAL_LEDGER")
                        .getStatus())
                .isEqualTo(202);
    }

    @Test
    void aStorageOutageWhileAWorkerDownloadsIsRetriedByTheQueueUntilItRecovers() throws Exception {
        faults.clear();
        Snapshot clean = snapshot(runScenario(newTenant()));
        Tenant t = newTenant();
        upload(t, "ledger.csv", ledgerCsv(), "INTERNAL_LEDGER");
        upload(t, "gateway.csv", gatewayCsv(), "GATEWAY");
        faults.fromHit(FaultPoints.STORAGE_DOWNLOAD, 1, () -> {
            throw SdkClientException.create("simulated: read timed out");
        });

        drain(t);
        assertThat(jdbc.queryForList(
                        "SELECT status FROM import_batch WHERE organization_id = ?", String.class, t.org()))
                .as("the batches wait (started, nothing committed); they don't fail while storage is down")
                .containsOnly("PROCESSING");
        assertThat(jdbc.queryForList("SELECT status FROM job WHERE organization_id = ?", String.class, t.org()))
                .containsOnly("QUEUED");

        faults.clear(); // storage is back
        finishFromHere(t);

        assertThat(snapshot(t)).isEqualTo(clean);
    }

    // ---------------------------------------------------------------------------------------------
    // The scenario

    private Tenant newTenant() {
        UUID org = data().newOrganization();
        return new Tenant(org, data().newMember(org, Role.UPLOADER).id());
    }

    private Tenant runScenario(Tenant t) throws Exception {
        assertThat(runScenarioRecoveringFromCrashes(t)).isZero();
        return t;
    }

    /** Uploads, imports, requests and runs the reconciliation; recovers from every crash; returns how many. */
    private int runScenarioRecoveringFromCrashes(Tenant t) throws Exception {
        upload(t, "ledger.csv", ledgerCsv(), "INTERNAL_LEDGER");
        upload(t, "gateway.csv", gatewayCsv(), "GATEWAY");
        int crashes = drainRecovering(t);
        requestRun(t);
        return crashes + drainRecovering(t);
    }

    /** After a transient-failure test: the run (if not requested yet) and the remaining work. */
    private void finishFromHere(Tenant t) throws Exception {
        for (int i = 0; i < 10 && pendingWork(t); i++) {
            makeRunnable(t);
            drain(t);
        }
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM reconciliation_run WHERE organization_id = ?", Integer.class, t.org())
                == 0) {
            requestRun(t);
        }
        for (int i = 0; i < 10 && pendingWork(t); i++) {
            makeRunnable(t);
            drain(t);
        }
        assertThat(pendingWork(t)).isFalse();
    }

    private int drainRecovering(Tenant t) {
        int crashes = 0;
        for (int i = 0; i < 20; i++) {
            try {
                drain(t);
                return crashes;
            } catch (SimulatedCrash e) {
                crashes++;
                // The worker is dead. Production: its lease expires, the sweeper re-queues the job,
                // and another worker resumes from the checkpoint.
                jdbc.update(
                        "UPDATE job SET lease_until = now() - interval '1 second' WHERE organization_id = ?"
                                + " AND status = 'RUNNING'",
                        t.org());
                jobRunner.sweepExpiredLeases();
                makeRunnable(t);
            }
        }
        throw new AssertionError("did not settle after 20 crashes");
    }

    private void drain(Tenant t) {
        jobRunner.runAllQueued(List.of(t.org()));
    }

    /** Skips backoffs and deferrals: a test can't wait minutes. */
    private void makeRunnable(Tenant t) {
        jdbc.update("UPDATE job SET run_after = now() WHERE organization_id = ? AND status = 'QUEUED'", t.org());
    }

    private boolean importsDone(Tenant t) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM import_batch WHERE organization_id = ?"
                                + " AND status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')",
                        Integer.class,
                        t.org())
                == 2;
    }

    private boolean pendingWork(Tenant t) {
        return jdbc.queryForObject(
                        "SELECT count(*) FROM job WHERE organization_id = ? AND status IN ('QUEUED', 'RUNNING')",
                        Integer.class,
                        t.org())
                > 0;
    }

    private void upload(Tenant t, String name, String csv, String sourceType) throws Exception {
        MockHttpServletResponse res = uploadResponse(t, name, csv, sourceType);
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
    }

    private MockHttpServletResponse uploadResponse(Tenant t, String name, String csv, String sourceType)
            throws Exception {
        return mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("sourceType", sourceType)
                        .with(as(t.user()))
                        .with(org(t.org())))
                .andReturn()
                .getResponse();
    }

    private void requestRun(Tenant t) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/reconciliation-runs")
                        .with(as(t.user()))
                        .with(org(t.org()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"periodStart\":\"" + DAY_1 + "\",\"periodEnd\":\"" + DAY_4
                                + "\",\"currency\":\"AED\"}"))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
    }

    /**
     * 250 references over four days: every 10th missing from the gateway, every 10th (+1) missing
     * from the ledger, every 10th (+2) with a different amount, every 10th (+3) twice in the gateway
     * file; the rest match. Plus five malformed ledger rows. 255 ledger rows and 250 gateway rows:
     * three chunks each.
     */
    static String ledgerCsv() {
        StringBuilder csv = new StringBuilder("reference,amount,currency,status,transaction_at\n");
        for (int i = 0; i < 250; i++) {
            if (i % 10 == 1) {
                continue;
            }
            csv.append(row("FI-" + i, amount(i), "AED", "SUCCESS", i));
            if (i % 50 == 0) {
                csv.append("BAD-")
                        .append(i)
                        .append(",abc,AED,SUCCESS,")
                        .append(at(i))
                        .append('\n');
            }
        }
        return csv.toString();
    }

    static String gatewayCsv() {
        StringBuilder csv = new StringBuilder("merchant_reference,amount,currency,status,transaction_at\n");
        for (int i = 0; i < 250; i++) {
            switch (i % 10) {
                case 0 -> {}
                case 2 -> csv.append(row("FI-" + i, amount(i).add(java.math.BigDecimal.ONE), "aed", "captured", i));
                case 3 -> {
                    csv.append(row("FI-" + i, amount(i), "aed", "captured", i));
                    csv.append(row("FI-" + i, amount(i), "aed", "captured", i));
                }
                default -> csv.append(row("FI-" + i, amount(i), "aed", "captured", i));
            }
        }
        return csv.toString();
    }

    private static java.math.BigDecimal amount(int i) {
        return new java.math.BigDecimal(100 + i).add(new java.math.BigDecimal("0.25"));
    }

    private static String row(String ref, java.math.BigDecimal amount, String currency, String status, int i) {
        return ref + "," + amount.toPlainString() + "," + currency + "," + status + "," + at(i) + "\n";
    }

    private static String at(int i) {
        return DAY_1.plusDays(i % 4) + "T08:" + "%02d".formatted(i % 60) + ":00Z";
    }

    // ---------------------------------------------------------------------------------------------
    // The snapshot: id-free, timestamp-free, canonical amounts, so two organizations compare equal.

    record Snapshot(Map<String, List<Map<String, Object>>> parts) {}

    private static final Map<String, String> SNAPSHOT_QUERIES = new LinkedHashMap<>();

    static {
        SNAPSHOT_QUERIES.put("records", """
                SELECT source_type, normalized_reference, trim_scale(amount)::text AS amount, currency,
                       transaction_date, source_row_number, is_duplicate_in_source
                  FROM transaction_record WHERE organization_id = ?
                 ORDER BY source_type, source_row_number""");
        SNAPSHOT_QUERIES.put("matches", """
                SELECT l.normalized_reference AS ledger, g.normalized_reference AS gateway, m.match_type,
                       trim_scale(m.amount_difference)::text AS difference
                  FROM reconciliation_match m
                  JOIN transaction_record l ON l.id = m.ledger_record_id AND l.transaction_date = m.ledger_txn_date
                  JOIN transaction_record g ON g.id = m.gateway_record_id AND g.transaction_date = m.gateway_txn_date
                 WHERE m.organization_id = ?
                 ORDER BY 1, 2""");
        SNAPSHOT_QUERIES.put("cases", """
                SELECT c.discrepancy_key, c.type, c.status, c.reason_code, trim_scale(c.amount)::text AS amount,
                       trim_scale(c.amount_difference)::text AS difference,
                       (SELECT count(*) FROM exception_case_record r WHERE r.exception_id = c.id) AS records,
                       (SELECT count(*) FROM exception_case_run cr WHERE cr.exception_id = c.id) AS runs,
                       (SELECT count(*) FROM exception_status_history h WHERE h.exception_id = c.id) AS history
                  FROM exception_case c WHERE c.organization_id = ?
                 ORDER BY c.discrepancy_key""");
        SNAPSHOT_QUERIES.put("batches", """
                SELECT source_type, status, total_rows, valid_rows, error_rows, last_committed_row, failure_reason
                  FROM import_batch WHERE organization_id = ? ORDER BY source_type""");
        SNAPSHOT_QUERIES.put("rowErrors", """
                SELECT b.source_type, e.row_number, e.column_name, e.error_code
                  FROM import_row_error e JOIN import_batch b ON b.id = e.batch_id
                 WHERE e.organization_id = ? ORDER BY 1, 2, 3, 4""");
        SNAPSHOT_QUERIES.put("runs", """
                SELECT status, matched_count, trim_scale(matched_amount)::text AS matched_amount, mismatch_count,
                       trim_scale(mismatch_net_difference)::text AS mismatch_net, unmatched_left_count,
                       trim_scale(unmatched_left_amount)::text AS left_amount, unmatched_right_count,
                       trim_scale(unmatched_right_amount)::text AS right_amount, duplicate_count,
                       currency_mismatch_count, excluded_count, last_completed_date, failure_reason
                  FROM reconciliation_run WHERE organization_id = ?""");
        SNAPSHOT_QUERIES.put("runDays", """
                SELECT d.day, d.matched_count, trim_scale(d.matched_amount)::text AS matched_amount, d.mismatch_count,
                       d.unmatched_left_count, d.unmatched_right_count, d.duplicate_count
                  FROM reconciliation_run_day d WHERE d.organization_id = ? ORDER BY d.day""");
        // One event per business transition: a duplicated or lost completion shows up here.
        SNAPSHOT_QUERIES.put("audit", """
                SELECT action, count(*) AS n FROM audit_event WHERE organization_id = ?
                   AND action NOT IN ('IMPORT_RECEIVED') GROUP BY action ORDER BY action""");
    }

    private Snapshot snapshot(Tenant t) {
        Map<String, List<Map<String, Object>>> parts = new LinkedHashMap<>();
        SNAPSHOT_QUERIES.forEach((name, sql) -> parts.put(name, jdbc.queryForList(sql, t.org())));
        Set<String> empty = new java.util.TreeSet<>();
        parts.forEach((name, rows) -> {
            if (rows.isEmpty() && !name.equals("rowErrors")) {
                empty.add(name);
            }
        });
        assertThat(empty).as("snapshot parts that should have rows").isEmpty();
        return new Snapshot(parts);
    }
}
