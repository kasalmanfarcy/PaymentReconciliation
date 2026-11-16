package com.reconflow.paymentreconflow.reporting;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.TestData;
import com.reconflow.paymentreconflow.support.TestData.TestUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A fresh organization for a report test (no TRUNCATE, ever): a member of every role that reads or
 * works cases, one accepted batch per source, records inserted as the importer stores them, and
 * runs queued as the API would queue them, then run by the real engine through the real queue.
 */
final class ReportFixture {

    final JdbcTemplate jdbc;
    final UUID org;
    final TestUser viewer;
    final TestUser investigator;
    final TestUser admin;
    final UUID ledgerBatch;
    final UUID gatewayBatch;
    private final AtomicInteger row = new AtomicInteger();

    ReportFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TestData data = new TestData(jdbc);
        this.org = data.newOrganization();
        this.viewer = data.newMember(org, Role.VIEWER);
        this.investigator = data.newMember(org, Role.INVESTIGATOR);
        this.admin = data.newMember(org, Role.ORG_ADMIN);
        this.ledgerBatch = data.newImportBatch(org, admin.id(), "INTERNAL_LEDGER", "COMPLETED");
        this.gatewayBatch = data.newImportBatch(org, admin.id(), "GATEWAY", "COMPLETED");
    }

    UUID ledger(String reference, String amount, LocalDate date) {
        return record(ledgerBatch, "INTERNAL_LEDGER", reference, amount, date);
    }

    UUID gateway(String reference, String amount, LocalDate date) {
        return record(gatewayBatch, "GATEWAY", reference, amount, date);
    }

    private UUID record(UUID batch, String source, String reference, String amount, LocalDate date) {
        UUID id = Ids.newId();
        jdbc.update(
                """
                INSERT INTO transaction_record (id, organization_id, batch_id, source_type, source_row_number,
                       raw_reference, normalized_reference, amount, currency, transaction_type, status,
                       transaction_date, raw_values)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'AED', 'PAYMENT', 'SUCCESS', ?, '{}'::jsonb)""", id, org, batch, source, row.incrementAndGet(), reference, reference, new BigDecimal(amount), date);
        return id;
    }

    /** A PENDING run and its RECONCILE job, as POST /reconciliation-runs writes them; then the queue runs it. */
    UUID run(JobRunner jobs, LocalDate from, LocalDate to) {
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, triggered_by)
                VALUES (?, ?, ?, ?, ?, ?, 'AED', ?)""", id, org, "client:" + id, "0".repeat(64), from, to, admin.id());
        jdbc.update(
                "INSERT INTO job (id, organization_id, type, payload) VALUES (?, ?, 'RECONCILE', ?::jsonb)",
                Ids.newId(),
                org,
                "{\"runId\":\"" + id + "\"}");
        jobs.runAllQueued(List.of(org));
        return id;
    }
}
