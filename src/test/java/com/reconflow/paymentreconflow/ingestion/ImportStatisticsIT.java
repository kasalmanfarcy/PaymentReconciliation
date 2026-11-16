package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M9.7: a bulk import refreshes the planner's statistics for the partitions it wrote, so a run
 * started right after it isn't planned as if the organization had no rows (docs/benchmarks.md §2.2:
 * one day per run took 45 s instead of 20 ms until autovacuum caught up).
 */
class ImportStatisticsIT extends AbstractImportIT {

    @Test
    void aLargeImportAnalyzesTheMonthsItWrote() throws Exception {
        Timestamp before = lastAnalyze();
        StringBuilder csv = new StringBuilder("reference,amount,currency,status,transaction_date\n");
        for (int i = 0; i < 1_000; i++) {
            csv.append("ST-").append(UUID.randomUUID()).append(",10.00,AED,SUCCESS,2026-10-15\n");
        }
        Tenant t = newTenant();
        uploadOk(t, "big.csv", utf8(csv.toString()), "INTERNAL_LEDGER");
        runQueue(t);

        Timestamp after = lastAnalyze();
        assertThat(after).isNotNull();
        if (before != null) {
            assertThat(after).isAfter(before);
        }
    }

    @Test
    void aSmallImportLeavesItToAutovacuum() throws Exception {
        Timestamp before = lastAnalyze();
        Tenant t = newTenant();
        uploadOk(t, "ledger-basic.csv", sample("ledger-basic.csv"), "INTERNAL_LEDGER");
        runQueue(t);
        assertThat(lastAnalyze()).isEqualTo(before);
    }

    private Timestamp lastAnalyze() {
        return jdbc.queryForObject(
                "SELECT last_analyze FROM pg_stat_user_tables WHERE relname = 'transaction_record_2026_10'",
                Timestamp.class);
    }
}
