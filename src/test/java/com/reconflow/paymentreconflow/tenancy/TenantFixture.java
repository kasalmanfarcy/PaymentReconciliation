package com.reconflow.paymentreconflow.tenancy;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.TestData;
import com.reconflow.paymentreconflow.support.TestData.TestUser;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two fresh organizations per case (no TRUNCATE, no shared state): A with an admin, B with a
 * member of every role. {@link #snapshotOrgB()} captures everything of B an attack could change.
 */
public final class TenantFixture {

    private final JdbcTemplate jdbc;
    private final UUID orgA;
    private final UUID orgB;
    private final TestUser orgAAdmin;
    private final Map<Role, TestUser> orgBMembers = new EnumMap<>(Role.class);
    private final UUID orgBBatch;
    private final UUID orgBFailedBatch;
    private final UUID orgBRecord;
    private final UUID orgBRun;
    private final UUID orgBFailedRun;
    private final UUID orgBCase;
    private final UUID orgBResolvedCase;

    private TenantFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TestData data = new TestData(jdbc);
        orgA = data.newOrganization("Org A " + UUID.randomUUID());
        orgB = data.newOrganization("Org B " + UUID.randomUUID());
        orgAAdmin = data.newMember(orgA, Role.ORG_ADMIN);
        for (Role role : Role.values()) {
            orgBMembers.put(role, data.newMember(orgB, role));
        }
        // Org B's financial data: a completed import with a record and a row error, and a failed
        // import (retryable), so every ingestion and ledger endpoint has something of B to aim at.
        UUID uploader = orgBMembers.get(Role.UPLOADER).id();
        orgBBatch = data.newImportBatch(orgB, uploader, "INTERNAL_LEDGER", "COMPLETED");
        orgBFailedBatch = data.newImportBatch(orgB, uploader, "GATEWAY", "FAILED");
        orgBRecord = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transaction_record (id, organization_id, batch_id, source_type, source_row_number,
                       raw_reference, normalized_reference, amount, currency, status, transaction_date, raw_values)
                VALUES (?, ?, ?, 'INTERNAL_LEDGER', 1, 'PAY-B', 'PAY-B', 10.0000, 'AED', 'SUCCESS',
                        DATE '2026-10-01', '{}'::jsonb)""", orgBRecord, orgB, orgBBatch);
        jdbc.update(
                "INSERT INTO import_row_error (id, organization_id, batch_id, row_number, column_name, error_code,"
                        + " message) VALUES (?, ?, ?, 2, 'amount', 'INVALID_AMOUNT', 'bad')",
                UUID.randomUUID(),
                orgB,
                orgBBatch);
        // Org B's runs: a completed one with a match, and a failed, retryable one.
        UUID admin = orgBMembers.get(Role.ORG_ADMIN).id();
        orgBRun = newRun(orgB, admin, "COMPLETED");
        orgBFailedRun = newRun(orgB, admin, "FAILED");
        UUID gatewayRecord = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transaction_record (id, organization_id, batch_id, source_type, source_row_number,
                       raw_reference, normalized_reference, amount, currency, status, transaction_date, raw_values)
                VALUES (?, ?, ?, 'GATEWAY', 2, 'PAY-B', 'PAY-B', 10.0000, 'AED', 'SUCCESS',
                        DATE '2026-10-01', '{}'::jsonb)""", gatewayRecord, orgB, orgBBatch);
        jdbc.update("""
                INSERT INTO reconciliation_match (id, organization_id, run_id, ledger_record_id, ledger_txn_date,
                       gateway_record_id, gateway_txn_date)
                VALUES (uuid_generate_v7(), ?, ?, ?, DATE '2026-10-01', ?, DATE '2026-10-01')""", orgB, orgBRun, orgBRecord, gatewayRecord);
        // Org B's exception cases (M7.18): an open one linked to its record, assigned to its
        // investigator, and a resolved one, so every exception endpoint has something of B to aim at.
        orgBCase = newCase(orgB, orgBRun, "AMOUNT_MISMATCH:PAY-B:PAYMENT:AED", "OPEN");
        jdbc.update(
                "UPDATE exception_case SET assigned_to = ? WHERE id = ?",
                orgBMembers.get(Role.INVESTIGATOR).id(),
                orgBCase);
        jdbc.update(
                "INSERT INTO exception_case_record (exception_id, transaction_record_id, transaction_date,"
                        + " organization_id) VALUES (?, ?, DATE '2026-10-01', ?)",
                orgBCase,
                orgBRecord,
                orgB);
        orgBResolvedCase = newCase(orgB, orgBRun, "MISSING_IN_LEDGER:PAY-B2:PAYMENT:AED", "RESOLVED");
        // M8: the completed run's day row, so a date-range report of A would pick it up if it ever
        // forgot the organization filter.
        jdbc.update("UPDATE reconciliation_run SET completed_at = now() WHERE id = ?", orgBRun);
        jdbc.update("""
                INSERT INTO reconciliation_run_day (run_id, organization_id, day, currency, matched_count,
                       matched_amount, mismatch_count, mismatch_net_difference, unmatched_left_count,
                       unmatched_left_amount, unmatched_right_count, unmatched_right_amount, duplicate_count,
                       currency_mismatch_count)
                VALUES (?, ?, DATE '2026-10-01', 'AED', 1, 10.0000, 1, -1.0000, 0, 0, 1, 10.0000, 0, 0)""", orgBRun, orgB);
    }

    /** A case of org B with its creation history row and run link, as DiscrepancyRecorder writes them. */
    private UUID newCase(UUID org, UUID run, String key, String status) {
        UUID id = UUID.randomUUID();
        boolean resolved = status.equals("RESOLVED");
        UUID resolver = orgBMembers.get(Role.INVESTIGATOR).id();
        jdbc.update(
                """
                INSERT INTO exception_case (id, organization_id, run_id, type, discrepancy_key, status, currency,
                       amount, resolution_code, resolved_by, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, 'AED', 10.0000, ?, ?, ?)""",
                id,
                org,
                run,
                key.substring(0, key.indexOf(':')),
                key,
                status,
                resolved ? "GATEWAY_FEE" : null,
                resolved ? resolver : null,
                resolved ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
        jdbc.update(
                "INSERT INTO exception_status_history (id, organization_id, exception_id, from_status, to_status)"
                        + " VALUES (?, ?, ?, NULL, 'OPEN')",
                UUID.randomUUID(),
                org,
                id);
        jdbc.update(
                "INSERT INTO exception_case_run (exception_id, run_id, organization_id, created, day)"
                        + " VALUES (?, ?, ?, true, DATE '2026-10-01')",
                id,
                run,
                org);
        return id;
    }

    /** A run row of org B in the given status, written with SQL as the API would have. */
    private UUID newRun(UUID org, UUID triggeredBy, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation_run (id, organization_id, idempotency_key, request_fingerprint,
                       period_start, period_end, currency, status, triggered_by)
                VALUES (?, ?, ?, ?, DATE '2026-10-01', DATE '2026-10-31', 'AED', ?, ?)""", id, org, "client:" + id, "0".repeat(64), status, triggeredBy);
        return id;
    }

    public static TenantFixture twoOrgs(JdbcTemplate jdbc) {
        return new TenantFixture(jdbc);
    }

    public UUID orgA() {
        return orgA;
    }

    public UUID orgB() {
        return orgB;
    }

    public TestUser orgAAdmin() {
        return orgAAdmin;
    }

    public TestUser orgBMember(Role role) {
        return orgBMembers.get(role);
    }

    /** Org B's completed import (with one record and one row error). */
    public UUID orgBBatch() {
        return orgBBatch;
    }

    /** Org B's failed, retryable import. */
    public UUID orgBFailedBatch() {
        return orgBFailedBatch;
    }

    public UUID orgBRecord() {
        return orgBRecord;
    }

    /** Org B's completed run (with one match). */
    public UUID orgBRun() {
        return orgBRun;
    }

    /** Org B's failed, retryable run. */
    public UUID orgBFailedRun() {
        return orgBFailedRun;
    }

    /** Org B's OPEN case, assigned to its investigator and linked to {@link #orgBRecord()}. */
    public UUID orgBCase() {
        return orgBCase;
    }

    /** Org B's RESOLVED case, reopenable by an admin of B. */
    public UUID orgBResolvedCase() {
        return orgBResolvedCase;
    }

    /** Every ID of org B that must never appear in a response to org A. */
    public List<UUID> orgBIds() {
        List<UUID> ids = new ArrayList<>();
        ids.add(orgB);
        ids.add(orgBBatch);
        ids.add(orgBFailedBatch);
        ids.add(orgBRecord);
        ids.add(orgBRun);
        ids.add(orgBFailedRun);
        ids.add(orgBCase);
        ids.add(orgBResolvedCase);
        orgBMembers.values().forEach(u -> ids.add(u.id()));
        return ids;
    }

    /** Org B's row, memberships, members' accounts, imports, runs, records, jobs, cases, notes, history and audit, as plain values. */
    public List<Map<String, Object>> snapshotOrgB() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbc.queryForList("select name, status from organization where id = ?", orgB));
        rows.addAll(jdbc.queryForList("""
                select m.user_id, m.role, u.status, u.password_hash, u.must_change_password, u.email,
                       (select count(*) from refresh_token t where t.user_id = u.id and t.revoked_at is not null) as revoked
                  from membership m join app_user u on u.id = m.user_id
                 where m.organization_id = ?
                 order by m.user_id
                """, orgB));
        rows.addAll(jdbc.queryForList("""
                select id, status, total_rows, valid_rows, error_rows, last_committed_row, failure_reason
                  from import_batch where organization_id = ? order by id
                """, orgB));
        rows.addAll(jdbc.queryForList("""
                select id, status, last_completed_date, failure_reason, matched_count
                  from reconciliation_run where organization_id = ? order by id
                """, orgB));
        rows.addAll(jdbc.queryForList("""
                select id, status, version, assigned_to, resolution_code, resolution_note, updated_at
                  from exception_case where organization_id = ? order by id
                """, orgB));
        rows.addAll(jdbc.queryForList("""
                select (select count(*) from exception_note where organization_id = ?) as notes,
                       (select count(*) from exception_status_history where organization_id = ?) as history,
                       (select count(*) from audit_event where organization_id = ?) as audit_events
                """, orgB, orgB, orgB));
        rows.addAll(jdbc.queryForList("""
                select (select count(*) from transaction_record where organization_id = ?) as records,
                       (select count(*) from import_row_error where organization_id = ?) as row_errors,
                       (select count(*) from job where organization_id = ?) as jobs
                """, orgB, orgB, orgB));
        return rows;
    }
}
