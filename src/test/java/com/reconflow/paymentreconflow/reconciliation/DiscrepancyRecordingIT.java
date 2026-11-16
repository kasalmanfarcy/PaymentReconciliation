package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.reconciliation.engine.RunContext;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M6.6: references that match with different amounts are an AMOUNT_MISMATCH case, never a match,
 * with {@code amount_difference = gateway − ledger}. The case is keyed on the discrepancy, so a
 * second run re-observes it instead of creating another (K3), and a resolved case is followed by a
 * new OPEN one (K4).
 */
class DiscrepancyRecordingIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired
    EngineCalls engine;

    @Test
    void pay103IsOneAmountMismatchCaseAndNoMatch() {
        var f = new ReconFixture(jdbc);
        UUID ledger = f.ledger("PAY-103", "750.00", "AED", DAY);
        UUID gateway = f.gateway("PAY-103", "700.00", "AED", DAY);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        engine.reconcileAndRecord(run, DAY);

        Map<String, Object> c = onlyCase(f.org);
        assertThat(c)
                .containsEntry("type", "AMOUNT_MISMATCH")
                .containsEntry("discrepancy_key", "AMOUNT_MISMATCH:PAY-103:PAYMENT:AED")
                .containsEntry("status", "OPEN")
                .containsEntry("run_id", run.runId())
                .containsEntry("currency", "AED")
                .containsEntry("version", 0L);
        assertThat((BigDecimal) c.get("amount_difference")).isEqualByComparingTo("-50.0000");
        assertThat(((BigDecimal) c.get("amount")).toPlainString()).isEqualTo("750.0000");
        assertThat(c.get("reason_code")).isNull();
        UUID caseId = (UUID) c.get("id");
        assertThat(linkedRecords(caseId)).containsExactlyInAnyOrder(ledger, gateway);
        assertThat(count("SELECT count(*) FROM reconciliation_match WHERE run_id = ?", run.runId()))
                .isZero();
        assertThat(jdbc.queryForMap(
                        "SELECT created FROM exception_case_run WHERE exception_id = ? AND run_id = ?",
                        caseId,
                        run.runId()))
                .containsEntry("created", true);
        // A new case starts its history (system actor) and its audit trail.
        assertThat(jdbc.queryForList(
                        "SELECT from_status, to_status, actor_id FROM exception_status_history WHERE exception_id = ?",
                        caseId))
                .singleElement()
                .satisfies(h -> assertThat(h)
                        .containsEntry("from_status", null)
                        .containsEntry("to_status", "OPEN")
                        .containsEntry("actor_id", null));
        assertThat(jdbc.queryForList(
                        "SELECT actor_id, payload->>'discrepancyKey' AS k, payload->>'runId' AS r FROM audit_event"
                                + " WHERE entity_id = ? AND action = 'EXCEPTION_CREATED'",
                        caseId))
                .singleElement()
                .satisfies(a -> assertThat(a)
                        .containsEntry("actor_id", null)
                        .containsEntry("k", "AMOUNT_MISMATCH:PAY-103:PAYMENT:AED")
                        .containsEntry("r", run.runId().toString()));
    }

    @Test
    void aSecondRunReobservesTheActiveCaseInsteadOfCreatingOne() {
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-103", "750.00", "AED", DAY);
        f.gateway("PAY-103", "700.00", "AED", DAY);
        RunContext first = engine.context(f, DAY, DAY, "AED");
        engine.reconcileAndRecord(first, DAY);
        complete(first);
        RunContext second = engine.context(f, DAY, DAY, "AED");

        engine.reconcileAndRecord(second, DAY);
        engine.reconcileAndRecord(second, DAY); // a re-executed chunk: still nothing new

        assertThat(count("SELECT count(*) FROM exception_case WHERE organization_id = ?", f.org))
                .isEqualTo(1);
        UUID caseId = (UUID) onlyCase(f.org).get("id");
        assertThat(jdbc.queryForList(
                        "SELECT run_id, created FROM exception_case_run WHERE exception_id = ? ORDER BY created DESC",
                        caseId))
                .containsExactly(
                        Map.of("run_id", first.runId(), "created", true),
                        Map.of("run_id", second.runId(), "created", false));
        assertThat(linkedRecords(caseId)).hasSize(2);
        assertThat(count(
                        "SELECT count(*) FROM audit_event WHERE entity_id = ? AND action = 'EXCEPTION_CREATED'",
                        caseId))
                .isEqualTo(1);
    }

    @Test
    void aResolvedDiscrepancyThatAppearsAgainGetsANewOpenCase() {
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-103", "750.00", "AED", DAY);
        f.gateway("PAY-103", "700.00", "AED", DAY);
        RunContext first = engine.context(f, DAY, DAY, "AED");
        engine.reconcileAndRecord(first, DAY);
        complete(first);
        UUID resolved = (UUID) onlyCase(f.org).get("id");
        jdbc.update(
                "UPDATE exception_case SET status = 'RESOLVED', resolution_code = 'GATEWAY_FEE', resolved_by = ?,"
                        + " resolved_at = now() WHERE id = ?",
                f.user,
                resolved);

        engine.reconcileAndRecord(engine.context(f, DAY, DAY, "AED"), DAY);

        assertThat(jdbc.queryForList(
                        "SELECT id, status FROM exception_case WHERE organization_id = ? ORDER BY created_at, id",
                        f.org))
                .extracting(r -> r.get("status"))
                .containsExactly("RESOLVED", "OPEN");
        assertThat(jdbc.queryForObject("SELECT status FROM exception_case WHERE id = ?", String.class, resolved))
                .isEqualTo("RESOLVED");
    }

    private void complete(RunContext run) {
        jdbc.update("UPDATE reconciliation_run SET status = 'COMPLETED' WHERE id = ?", run.runId());
    }

    private Map<String, Object> onlyCase(UUID org) {
        return jdbc.queryForMap("SELECT * FROM exception_case WHERE organization_id = ?", org);
    }

    private List<UUID> linkedRecords(UUID caseId) {
        return jdbc.queryForList(
                "SELECT transaction_record_id FROM exception_case_record WHERE exception_id = ?", UUID.class, caseId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
