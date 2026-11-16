package com.reconflow.paymentreconflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5.6: the indexes V1 created serve this phase's queries. Plans on an empty table are meaningless
 * (the planner prefers a sequential scan for a handful of rows), so this loads 120,000 rows for one
 * organization, two months, and ANALYZEs first. The plans are printed and recorded in
 * docs/database-design.md §3.3.
 */
class TransactionQueryPlanIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionQueryPlanIT.class);

    private UUID org;

    @BeforeEach
    void load() {
        org = data().newOrganization();
        UUID user = data().newMember(org, Role.UPLOADER).id();
        UUID batch = data().newImportBatch(org, user, "GATEWAY", "COMPLETED");
        // 60k rows in each of September and October 2026, both sources, ~2k references a day.
        jdbc.update("""
                INSERT INTO transaction_record (id, organization_id, batch_id, source_type, source_row_number,
                       raw_reference, normalized_reference, amount, currency, status, transaction_date, raw_values)
                SELECT gen_random_uuid(), ?, ?, CASE WHEN g % 2 = 0 THEN 'GATEWAY' ELSE 'INTERNAL_LEDGER' END, g,
                       'PAY-' || g, 'PAY-' || g, (g % 1000) + 0.5, 'AED', 'SUCCESS',
                       DATE '2026-09-01' + (g % 61), '{}'::jsonb
                  FROM generate_series(1, 120000) AS g
                """, org, batch);
        jdbc.execute("ANALYZE transaction_record");
    }

    @Test
    void aDateRangeSearchTouchesOnePartitionThroughItsIndex() {
        List<String> plan = explain("""
                SELECT t.id FROM transaction_record t JOIN import_batch b ON b.id = t.batch_id
                 WHERE t.organization_id = '%s' AND t.source_type = 'GATEWAY'
                   AND t.transaction_date BETWEEN DATE '2026-10-01' AND DATE '2026-10-31'
                   AND b.status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS')
                 ORDER BY t.transaction_date, t.id LIMIT 51
                """.formatted(org));

        String text = String.join("\n", plan);
        assertThat(text).contains("transaction_record_2026_10").doesNotContain("transaction_record_2026_09");
        assertThat(text).containsPattern("Index (Only )?Scan|Bitmap Index Scan");
    }

    @Test
    void aReferenceLookupWithinADayUsesTheMatchIndex() {
        // The engine's per-day shape (M6): one partition, the match index's full prefix.
        List<String> plan = explain("""
                SELECT t.id FROM transaction_record t
                 WHERE t.organization_id = '%s' AND t.source_type = 'GATEWAY'
                   AND t.transaction_date = DATE '2026-10-15' AND t.normalized_reference = 'PAY-1000'
                """.formatted(org));

        String text = String.join("\n", plan);
        assertThat(text).contains("transaction_record_2026_10").doesNotContain("transaction_record_2026_09");
        assertThat(text).containsPattern("Index (Only )?Scan using transaction_record_2026_10_organization_id");
    }

    private List<String> explain(String sql) {
        List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + sql, String.class);
        log.info("Plan:\n{}", String.join("\n", plan));
        return plan;
    }
}
