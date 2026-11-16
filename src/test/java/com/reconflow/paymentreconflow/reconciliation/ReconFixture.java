package com.reconflow.paymentreconflow.reconciliation;

import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.TestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Engine fixtures written with plain SQL: a fresh organization (no TRUNCATE, ever), an investigator,
 * one accepted batch per source, and records inserted exactly as the importer would have stored
 * them (normalized reference, scale-4 amount, UTC date). Tests then assert on the engine's output
 * for every record they inserted.
 */
final class ReconFixture {

    final JdbcTemplate jdbc;
    final UUID org;
    final UUID user;
    final UUID ledgerBatch;
    final UUID gatewayBatch;
    private final AtomicInteger row = new AtomicInteger();

    ReconFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TestData data = new TestData(jdbc);
        this.org = data.newOrganization();
        this.user = data.newMember(org, Role.INVESTIGATOR).id();
        this.ledgerBatch = data.newImportBatch(org, user, "INTERNAL_LEDGER", "COMPLETED");
        this.gatewayBatch = data.newImportBatch(org, user, "GATEWAY", "COMPLETED");
    }

    UUID ledger(String reference, String amount, String currency, LocalDate date) {
        return record(SourceType.INTERNAL_LEDGER, reference, amount, currency, date, "SUCCESS", "PAYMENT", false);
    }

    UUID gateway(String reference, String amount, String currency, LocalDate date) {
        return record(SourceType.GATEWAY, reference, amount, currency, date, "SUCCESS", "PAYMENT", false);
    }

    UUID record(
            SourceType source,
            String reference,
            String amount,
            String currency,
            LocalDate date,
            String status,
            String type,
            boolean flaggedDuplicate) {
        return recordInBatch(
                source == SourceType.INTERNAL_LEDGER ? ledgerBatch : gatewayBatch,
                source,
                reference,
                amount,
                currency,
                date,
                status,
                type,
                flaggedDuplicate);
    }

    UUID recordInBatch(
            UUID batch,
            SourceType source,
            String reference,
            String amount,
            String currency,
            LocalDate date,
            String status,
            String type,
            boolean flaggedDuplicate) {
        UUID id = Ids.newId();
        jdbc.update(
                """
                INSERT INTO transaction_record (id, organization_id, batch_id, source_type, source_row_number,
                       raw_reference, normalized_reference, amount, currency, transaction_type, status,
                       transaction_date, is_duplicate_in_source, raw_values)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)""",
                id,
                org,
                batch,
                source.name(),
                row.incrementAndGet(),
                reference,
                reference,
                new BigDecimal(amount),
                currency,
                type,
                status,
                date,
                flaggedDuplicate);
        return id;
    }

    /** A batch in another status (PROCESSING, FAILED...), for rule P3. */
    UUID batch(String sourceType, String status) {
        return new TestData(jdbc).newImportBatch(org, user, sourceType, status);
    }

    /** A PENDING run row, as POST /reconciliation-runs would have written it. */
    UUID pendingRun(LocalDate from, LocalDate to, String currency) {
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, triggered_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, org, "client:" + id, "0".repeat(64), from, to, currency, user);
        return id;
    }
}
