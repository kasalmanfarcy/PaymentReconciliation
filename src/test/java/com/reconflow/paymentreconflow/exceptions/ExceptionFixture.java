package com.reconflow.paymentreconflow.exceptions;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.TestData;
import com.reconflow.paymentreconflow.support.TestData.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exception cases made the way production makes them: records inserted as the importer stores
 * them, then a real reconciliation run through the job queue, so every case comes out of the real
 * engine and {@code DiscrepancyRecorder} (phase-M7 §5), with its first history row and its
 * {@code EXCEPTION_CREATED} audit event. A fresh organization each time, with a member of every
 * role; nothing is ever truncated.
 */
public final class ExceptionFixture {

    public static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    private final JdbcTemplate jdbc;
    private final JobRunner jobs;
    public final UUID org;
    private final Map<Role, TestUser> members = new EnumMap<>(Role.class);
    private final UUID ledgerBatch;
    private final UUID gatewayBatch;
    private final AtomicInteger row = new AtomicInteger();

    public ExceptionFixture(JdbcTemplate jdbc, JobRunner jobs) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        TestData data = new TestData(jdbc);
        this.org = data.newOrganization();
        for (Role role : Role.values()) {
            members.put(role, data.newMember(org, role));
        }
        this.ledgerBatch = data.newImportBatch(org, uploader(), "INTERNAL_LEDGER", "COMPLETED");
        this.gatewayBatch = data.newImportBatch(org, uploader(), "GATEWAY", "COMPLETED");
    }

    public UUID admin() {
        return members.get(Role.ORG_ADMIN).id();
    }

    public UUID investigator() {
        return members.get(Role.INVESTIGATOR).id();
    }

    public UUID uploader() {
        return members.get(Role.UPLOADER).id();
    }

    public UUID viewer() {
        return members.get(Role.VIEWER).id();
    }

    public UUID member(Role role) {
        return members.get(role).id();
    }

    public UUID ledger(String reference, String amount) {
        return record("INTERNAL_LEDGER", reference, amount, DAY);
    }

    public UUID gateway(String reference, String amount) {
        return record("GATEWAY", reference, amount, DAY);
    }

    public UUID record(String source, String reference, String amount, LocalDate date) {
        UUID id = Ids.newId();
        jdbc.update(
                """
                INSERT INTO transaction_record (id, organization_id, batch_id, source_type, source_row_number,
                       raw_reference, normalized_reference, amount, currency, transaction_type, status,
                       transaction_date, raw_values)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'AED', 'PAYMENT', 'SUCCESS', ?, ?::jsonb)""",
                id,
                org,
                source.equals("GATEWAY") ? gatewayBatch : ledgerBatch,
                source,
                row.incrementAndGet(),
                " " + reference.toLowerCase() + " ",
                reference,
                new BigDecimal(amount),
                date,
                "{\"reference\":\"" + reference.toLowerCase() + "\",\"amount\":\"" + amount + "\"}");
        return id;
    }

    /** A run over {@link #DAY} in AED, requested and processed synchronously; returns the run id. */
    public UUID reconcile() {
        return reconcile(DAY, DAY);
    }

    public UUID reconcile(LocalDate from, LocalDate to) {
        UUID run = Ids.newId();
        jdbc.update("""
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, triggered_by)
                VALUES (?, ?, ?, ?, ?, ?, 'AED', ?)""", run, org, "client:" + run, "0".repeat(64), from, to, investigator());
        jdbc.update(
                "INSERT INTO job (id, organization_id, type, payload) VALUES (?, ?, 'RECONCILE', ?::jsonb)",
                Ids.newId(),
                org,
                "{\"runId\":\"" + run + "\"}");
        jobs.runAllQueued(List.of(org));
        String status = jdbc.queryForObject("SELECT status FROM reconciliation_run WHERE id = ?", String.class, run);
        if (!"COMPLETED".equals(status)) {
            throw new IllegalStateException("run " + run + " ended " + status);
        }
        return run;
    }

    /** The PAY-103 demo case: ledger 750.00, gateway 700.00, one AMOUNT_MISMATCH; returns its id. */
    public UUID amountMismatch(String reference) {
        ledger(reference, "750.00");
        gateway(reference, "700.00");
        reconcile();
        return caseId("AMOUNT_MISMATCH:" + reference + ":PAYMENT:AED");
    }

    /** The newest case with this key. */
    public UUID caseId(String discrepancyKey) {
        return jdbc.queryForObject(
                "SELECT id FROM exception_case WHERE organization_id = ? AND discrepancy_key = ?"
                        + " ORDER BY created_at DESC, id DESC LIMIT 1",
                UUID.class,
                org,
                discrepancyKey);
    }

    public Map<String, Object> caseRow(UUID caseId) {
        return jdbc.queryForMap("SELECT * FROM exception_case WHERE id = ?", caseId);
    }

    public long version(UUID caseId) {
        return jdbc.queryForObject("SELECT version FROM exception_case WHERE id = ?", Long.class, caseId);
    }

    public String status(UUID caseId) {
        return jdbc.queryForObject("SELECT status FROM exception_case WHERE id = ?", String.class, caseId);
    }

    public long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    public long historyRows(UUID caseId) {
        return count("SELECT count(*) FROM exception_status_history WHERE exception_id = ?", caseId);
    }

    public long auditRows(UUID caseId, String action) {
        return count(
                "SELECT count(*) FROM audit_event WHERE entity_type = 'EXCEPTION_CASE' AND entity_id = ? AND action = ?",
                caseId,
                action);
    }

    public long auditRows(UUID caseId) {
        return count("SELECT count(*) FROM audit_event WHERE entity_type = 'EXCEPTION_CASE' AND entity_id = ?", caseId);
    }
}
