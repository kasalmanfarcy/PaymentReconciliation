package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Catches the most likely future leak: a new tenant-owned table shipped without a policy. Any
 * table with an {@code organization_id} column must have RLS enabled, forced, and at least one
 * policy, except the documented exceptions.
 */
class RlsCoverageIT extends AbstractIntegrationTest {

    /** Read before any tenant is known, or cross-tenant by design (see V4's header). */
    static final List<String> EXEMPT = List.of("job", "membership");

    @Test
    void everyTenantOwnedTableHasAForcedPolicy() {
        List<Map<String, Object>> tables = jdbc.queryForList("""
                SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity,
                       (SELECT count(*) FROM pg_policies p
                         WHERE p.schemaname = 'public' AND p.tablename = c.relname) AS policies
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped
                 WHERE c.relkind IN ('r', 'p')
                   AND NOT c.relispartition       -- partitions inherit the parent's policy via the parent
                """);

        assertThat(tables).extracting(t -> t.get("relname")).contains("import_batch", "audit_event", "exception_case");
        for (Map<String, Object> t : tables) {
            if (EXEMPT.contains((String) t.get("relname"))) {
                continue;
            }
            assertThat(t)
                    .as("table %s needs ENABLE + FORCE ROW LEVEL SECURITY and a policy (see V4)", t.get("relname"))
                    .containsEntry("relrowsecurity", true)
                    .containsEntry("relforcerowsecurity", true);
            assertThat((Long) t.get("policies"))
                    .as("policies on %s", t.get("relname"))
                    .isPositive();
        }
    }
}
