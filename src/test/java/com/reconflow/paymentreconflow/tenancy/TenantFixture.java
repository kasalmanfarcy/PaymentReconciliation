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

    /** Every ID of org B that must never appear in a response to org A. */
    public List<UUID> orgBIds() {
        List<UUID> ids = new ArrayList<>();
        ids.add(orgB);
        ids.add(orgBBatch);
        ids.add(orgBFailedBatch);
        ids.add(orgBRecord);
        orgBMembers.values().forEach(u -> ids.add(u.id()));
        return ids;
    }

    /** Org B's row, memberships, members' accounts, imports, records and jobs, as plain values. */
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
                select (select count(*) from transaction_record where organization_id = ?) as records,
                       (select count(*) from import_row_error where organization_id = ?) as row_errors,
                       (select count(*) from job where organization_id = ?) as jobs
                """, orgB, orgB, orgB));
        return rows;
    }
}
