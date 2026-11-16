package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Read-side membership queries that need the organization's name or status in the same query. */
@Component
public class MembershipQueries {

    /** One of the user's usable memberships, as listed in the login response. */
    public record MembershipSummary(UUID organizationId, String name, Role role) {}

    private final JdbcClient jdbc;

    MembershipQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Memberships in ACTIVE organizations only: a suspended organization answers 404 to its
     * members, so offering it at login would only lead the client into an error.
     */
    public List<MembershipSummary> activeMembershipsOf(UUID userId) {
        return jdbc.sql("""
                        SELECT m.organization_id, o.name, m.role
                          FROM membership m
                          JOIN organization o ON o.id = m.organization_id
                         WHERE m.user_id = :userId AND o.status = 'ACTIVE'
                         ORDER BY o.name, m.organization_id
                        """)
                .param("userId", userId)
                .query((rs, n) -> new MembershipSummary(
                        rs.getObject("organization_id", UUID.class),
                        rs.getString("name"),
                        Role.valueOf(rs.getString("role"))))
                .list();
    }
}
