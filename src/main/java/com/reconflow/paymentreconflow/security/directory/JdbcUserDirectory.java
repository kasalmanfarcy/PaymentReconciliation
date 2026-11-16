package com.reconflow.paymentreconflow.security.directory;

import com.reconflow.paymentreconflow.security.authz.RolePermissions;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@link UserDirectory} over {@code membership} and {@code app_user}, the tables {@code security}
 * owns. Roles become permissions through {@link RolePermissions}, the only place they do.
 */
@Component
class JdbcUserDirectory implements UserDirectory {

    private final JdbcClient jdbc;

    JdbcUserDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActiveMemberWith(UUID organizationId, UUID userId, Permission permission) {
        List<String> roles = jdbc.sql("""
                        SELECT m.role FROM membership m JOIN app_user u ON u.id = m.user_id
                         WHERE m.organization_id = :org AND m.user_id = :user AND u.status = 'ACTIVE'
                        """)
                .param("org", organizationId)
                .param("user", userId)
                .query(String.class)
                .list();
        return roles.stream()
                .map(Role::valueOf)
                .anyMatch(r -> RolePermissions.of(r).contains(permission));
    }

    @Override
    public Map<UUID, String> displayNames(Collection<UUID> userIds) {
        Map<UUID, String> names = new HashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, display_name FROM app_user WHERE id IN (:ids)")
                .param("ids", List.copyOf(userIds))
                .query(rs -> {
                    names.put(rs.getObject("id", UUID.class), rs.getString("display_name"));
                });
        return names;
    }
}
