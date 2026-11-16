package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.id.Ids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M7.3: {@code exception_case_active_uk} keeps one active case per discrepancy on every path that
 * creates or reactivates one: plain SQL, the engine re-running (K3/K4), and a reopen racing a run.
 */
class ExceptionActiveCaseIT extends AbstractExceptionApiIT {

    static final String KEY = "AMOUNT_MISMATCH:PAY-103:PAYMENT:AED";

    @Autowired
    DataSource dataSource;

    @Test
    void theDatabaseRefusesASecondActiveCaseForOneKey() {
        var f = fixture();
        UUID first = f.amountMismatch("PAY-103");
        UUID run = (UUID) f.caseRow(first).get("run_id");

        assertThatThrownBy(() -> insertCase(f, run, "IN_REVIEW"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("exception_case_active_uk");

        // Resolved cases are outside the partial index: history can hold any number of them.
        jdbc.update(
                "UPDATE exception_case SET status = 'RESOLVED', resolution_code = 'GATEWAY_FEE', resolved_by = ?,"
                        + " resolved_at = now() WHERE id = ?",
                f.investigator(),
                first);
        insertCase(f, run, "OPEN");
        assertThat(f.count(
                        "SELECT count(*) FROM exception_case WHERE organization_id = ? AND discrepancy_key = ?",
                        f.org,
                        KEY))
                .isEqualTo(2);
    }

    @Test
    void aRerunReobservesAnActiveCaseWithoutTouchingIt() throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        patchCase(
                f,
                id,
                f.investigator(),
                "\"0\"",
                "{\"assignedTo\":\"" + f.investigator() + "\",\"status\":\"IN_REVIEW\"}");
        Map<String, Object> before = f.caseRow(id);

        UUID rerun = f.reconcile();

        // K3: the same case, linked to the new run; its status, assignee and version are untouched,
        // so a re-run never makes an investigator's If-Match stale.
        assertThat(f.count(
                        "SELECT count(*) FROM exception_case WHERE organization_id = ? AND discrepancy_key = ?",
                        f.org,
                        KEY))
                .isOne();
        assertThat(f.caseRow(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "SELECT created FROM exception_case_run WHERE exception_id = ? AND run_id = ?",
                        Boolean.class,
                        id,
                        rerun))
                .isFalse();
        assertThat(f.historyRows(id)).isEqualTo(2);
    }

    @Test
    void aRerunAfterAResolutionOpensOneNewCaseAndLeavesTheResolvedOneAlone() throws Exception {
        var f = fixture();
        UUID resolved = f.amountMismatch("PAY-103");
        resolve(f, resolved, f.investigator(), "\"0\"", "GATEWAY_FEE", null);
        Map<String, Object> before = f.caseRow(resolved);

        f.reconcile();
        UUID newer = f.caseId(KEY);
        f.reconcile(); // and again: the new case is re-observed, not duplicated

        // K4: the discrepancy is still there, so someone should look again, in a new case; the old
        // case and its resolution are never reopened or changed by the engine.
        assertThat(newer).isNotEqualTo(resolved);
        assertThat(f.caseRow(resolved)).isEqualTo(before);
        assertThat(f.status(newer)).isEqualTo("OPEN");
        assertThat(jdbc.queryForList(
                        "SELECT status FROM exception_case WHERE organization_id = ? AND discrepancy_key = ? ORDER BY created_at",
                        String.class,
                        f.org,
                        KEY))
                .containsExactly("RESOLVED", "OPEN");
    }

    @Test
    void aReopenRacingARunIsStoppedByTheIndex() throws Exception {
        var f = fixture();
        UUID resolved = f.amountMismatch("PAY-103");
        resolve(f, resolved, f.investigator(), "\"0\"", "GATEWAY_FEE", null);
        UUID run = (UUID) f.caseRow(resolved).get("run_id");

        // A "run" inserts a new OPEN case for the key and hasn't committed yet: the reopen's
        // pre-check can't see it, so only the unique index can stop the second active case.
        try (Connection runTx = dataSource.getConnection()) {
            runTx.setAutoCommit(false);
            // What the engine's chunk transaction does first: bind its tenant (RLS, M10.7).
            try (PreparedStatement bind = runTx.prepareStatement("SELECT set_config('app.org_id', ?, true)")) {
                bind.setString(1, f.org.toString());
                bind.execute();
            }
            UUID competing = Ids.newId();
            try (PreparedStatement ps = runTx.prepareStatement("""
                    INSERT INTO exception_case (id, organization_id, run_id, type, discrepancy_key, status, currency, amount)
                    VALUES (?, ?, ?, 'AMOUNT_MISMATCH', ?, 'OPEN', 'AED', 750)""")) {
                ps.setObject(1, competing);
                ps.setObject(2, f.org);
                ps.setObject(3, run);
                ps.setString(4, KEY);
                ps.executeUpdate();
            }

            CompletableFuture<MockHttpServletResponse> reopen = CompletableFuture.supplyAsync(
                    () -> call(() -> reopen(f, resolved, f.admin(), "\"1\"", "fee was wrong")));
            // The reopen's UPDATE now waits on the uncommitted index entry; commit the run's insert.
            awaitABlockedQuery();
            runTx.commit();

            MockHttpServletResponse res = reopen.get(30, TimeUnit.SECONDS);
            assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(409);
            assertThat(json(res))
                    .containsEntry("code", "active-case-exists")
                    .containsEntry("existingCaseId", competing.toString());
        }
        assertThat(f.status(resolved)).isEqualTo("RESOLVED");
        assertThat(f.version(resolved)).isOne();
        assertThat(f.auditRows(resolved, "EXCEPTION_REOPENED")).isZero();
    }

    private void insertCase(ExceptionFixture f, UUID run, String status) {
        jdbc.update(
                "INSERT INTO exception_case (id, organization_id, run_id, type, discrepancy_key, status, currency, amount)"
                        + " VALUES (?, ?, ?, 'AMOUNT_MISMATCH', ?, ?, 'AED', 750)",
                Ids.newId(),
                f.org,
                run,
                KEY,
                status);
    }

    private void awaitABlockedQuery() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Long waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                            + " AND wait_event_type = 'Lock' AND query ILIKE 'update exception_case%'",
                    Long.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the reopen never blocked on the index");
    }

    interface Call {
        MockHttpServletResponse run() throws Exception;
    }

    private static MockHttpServletResponse call(Call call) {
        try {
            return call.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
