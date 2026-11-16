package com.reconflow.paymentreconflow.security.tenant;

import com.reconflow.paymentreconflow.security.user.UserStatus;
import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The per-request lookup behind TenantContextFilter: one query, never cached. Fresh on every
 * request is the design (authentication.plan §1): disabling a user, removing a membership or
 * changing a role takes effect on the next request, not when the token expires. One join, not two
 * round trips: at ~35 req/s a second query per request would double the cost for nothing.
 *
 * <p>No transaction: app_user, membership and organization have no row-level security, because
 * at this point no tenant is known yet.
 */
@Component
public class TenantLookup {

    /** The user, plus every membership (empty when the user has none). */
    public record UserMemberships(UserStatus status, boolean platformAdmin, List<MembershipRow> memberships) {}

    public record MembershipRow(UUID organizationId, Role role, boolean organizationActive) {}

    private final JdbcClient jdbc;

    TenantLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserMemberships> find(UUID userId) {
        record Row(
                UserStatus status,
                boolean platformAdmin,
                @Nullable UUID organizationId,
                @Nullable Role role,
                boolean organizationActive) {}
        List<Row> rows = jdbc.sql("""
                        SELECT u.status, u.is_platform_admin, m.organization_id, m.role, o.status AS org_status
                          FROM app_user u
                          LEFT JOIN membership m ON m.user_id = u.id
                          LEFT JOIN organization o ON o.id = m.organization_id
                         WHERE u.id = :userId
                        """)
                .param("userId", userId)
                .query((rs, n) -> new Row(
                        UserStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("is_platform_admin"),
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("role") == null ? null : Role.valueOf(rs.getString("role")),
                        "ACTIVE".equals(rs.getString("org_status"))))
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<MembershipRow> memberships = new ArrayList<>();
        for (Row row : rows) {
            if (row.organizationId() != null && row.role() != null) {
                memberships.add(new MembershipRow(row.organizationId(), row.role(), row.organizationActive()));
            }
        }
        Row first = rows.getFirst();
        return Optional.of(new UserMemberships(first.status(), first.platformAdmin(), memberships));
    }
}
