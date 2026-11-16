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

    private TenantFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        TestData data = new TestData(jdbc);
        orgA = data.newOrganization("Org A " + UUID.randomUUID());
        orgB = data.newOrganization("Org B " + UUID.randomUUID());
        orgAAdmin = data.newMember(orgA, Role.ORG_ADMIN);
        for (Role role : Role.values()) {
            orgBMembers.put(role, data.newMember(orgB, role));
        }
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

    /** Every ID of org B that must never appear in a response to org A. */
    public List<UUID> orgBIds() {
        List<UUID> ids = new ArrayList<>();
        ids.add(orgB);
        orgBMembers.values().forEach(u -> ids.add(u.id()));
        return ids;
    }

    /** Org B's row, its memberships and its members' accounts, as plain values. */
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
        return rows;
    }
}
