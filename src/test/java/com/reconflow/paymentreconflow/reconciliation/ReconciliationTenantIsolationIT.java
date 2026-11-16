package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.reconciliation.engine.SqlScripts;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.RlsProbe;
import java.sql.Array;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * M6.21: a record of organization B never takes part in organization A's run. Layer 1 is the
 * explicit {@code organization_id = :org} in every engine query; layer 2 is row-level security,
 * proven here by running a test-only copy of the classify query with those predicates removed, as
 * a non-superuser role (superusers bypass RLS). The API side (B can't see or retry A's runs) is in
 * the cross-tenant suite (ReconciliationCrossTenantCases).
 */
class ReconciliationTenantIsolationIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired
    RunDriver driver;

    @Autowired
    DataSource dataSource;

    static List<UUID> identicalRecords(ReconFixture f) {
        return List.of(
                f.ledger("PAY-101", "1000.00", "AED", DAY),
                f.gateway("PAY-101", "1000.00", "AED", DAY),
                f.ledger("PAY-102", "500.00", "AED", DAY),
                f.ledger("PAY-103", "750.00", "AED", DAY),
                f.gateway("PAY-103", "700.00", "AED", DAY));
    }

    @Test
    void orgBsIdenticalRecordsNeverTakePartInOrgAsRun() {
        var a = new ReconFixture(jdbc);
        var b = new ReconFixture(jdbc);
        List<UUID> aRecords = identicalRecords(a);
        List<UUID> bRecords = identicalRecords(b);

        UUID run = driver.run(a, DAY, DAY, "AED");

        List<UUID> touched = new ArrayList<>(jdbc.queryForList(
                "SELECT unnest(ARRAY[ledger_record_id, gateway_record_id]) FROM reconciliation_match WHERE run_id = ?",
                UUID.class,
                run));
        touched.addAll(jdbc.queryForList(
                "SELECT r.transaction_record_id FROM exception_case_run cr"
                        + " JOIN exception_case_record r ON r.exception_id = cr.exception_id WHERE cr.run_id = ?",
                UUID.class,
                run));
        assertThat(touched).containsExactlyInAnyOrderElementsOf(aRecords).doesNotContainAnyElementsOf(bRecords);
        assertThat(jdbc.queryForList(
                        "SELECT DISTINCT organization_id FROM reconciliation_match WHERE run_id = ?"
                                + " UNION SELECT DISTINCT organization_id FROM exception_case_run WHERE run_id = ?",
                        UUID.class,
                        run,
                        run))
                .containsExactly(a.org);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM exception_case WHERE organization_id = ?", Long.class, b.org))
                .isZero();
        assertThat(driver.summary(run)).containsEntry("matched_count", 1).containsEntry("mismatch_count", 1);
    }

    @Test
    void rowLevelSecurityAloneKeepsOrgBOutOfTheClassifyQuery() throws Exception {
        var a = new ReconFixture(jdbc);
        var b = new ReconFixture(jdbc);
        List<UUID> aRecords = identicalRecords(a);
        List<UUID> bRecords = identicalRecords(b);
        String unscoped = withoutTenantFilter(SqlScripts.load("classify_groups.sql"));
        Map<String, Object> params = Map.of(
                "org",
                a.org, // still bound for the remaining :org uses; there are none left
                "day",
                DAY,
                "periodStart",
                DAY,
                "periodEnd",
                DAY,
                "leftSource",
                "INTERNAL_LEDGER",
                "rightSource",
                "GATEWAY",
                "currency",
                "AED");

        // As a superuser (RLS bypassed), the unscoped copy sees every tenant's PAY-10x groups in the
        // shared test database, A's and B's included: it really has no tenant filter.
        assertThat(recordIds(new NamedParameterJdbcTemplate(jdbc), unscoped, params))
                .containsAll(aRecords)
                .containsAll(bRecords);

        RlsProbe probe = new RlsProbe(dataSource, jdbc);
        List<UUID> asTenantA =
                probe.asTenant(a.org, j -> recordIds(new NamedParameterJdbcTemplate(j), unscoped, params));
        List<UUID> asNobody = probe.asTenant(null, j -> recordIds(new NamedParameterJdbcTemplate(j), unscoped, params));

        assertThat(asTenantA).containsExactlyInAnyOrderElementsOf(aRecords);
        assertThat(asNobody).isEmpty();
    }

    /**
     * The test-only copy: the classify query's SELECT, with every {@code organization_id = :org}
     * predicate replaced by {@code true}. Nothing but RLS stands between it and other tenants.
     */
    static String withoutTenantFilter(String classify) {
        String select = classify.substring(classify.indexOf("WITH day_keys"));
        String unscoped = select.replaceAll("t\\.organization_id\\s*=\\s*:org", "true");
        assertThat(unscoped).doesNotContain(":org").contains("anchor_date = :day");
        return unscoped;
    }

    private static List<UUID> recordIds(NamedParameterJdbcTemplate jdbc, String sql, Map<String, Object> params) {
        List<UUID> ids = new ArrayList<>();
        jdbc.query(sql, params, rs -> {
            for (String column : Set.of("ledger_ids", "gateway_ids")) {
                Array array = rs.getArray(column);
                if (array != null) {
                    ids.addAll(List.of((UUID[]) array.getArray()));
                }
            }
        });
        return ids;
    }
}
