package com.reconflow.paymentreconflow.organization;

import com.reconflow.paymentreconflow.shared.context.TenantDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** {@code organization} has no row-level security (V4): it is read before any tenant is known. */
@Component
class JdbcTenantDirectory implements TenantDirectory {

    private final JdbcTemplate jdbc;

    JdbcTenantDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> organizationIds() {
        return jdbc.queryForList("SELECT id FROM organization ORDER BY id", UUID.class);
    }
}
