package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.exceptions.Discrepancy;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.engine.DayResult;
import com.reconflow.paymentreconflow.reconciliation.engine.RunContext;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M6.5: same normalized reference, same transaction type, same currency and same amount (by value)
 * is an EXACT match; anything less is not. On projectoverview.md §5's data, PAY-101 is the only
 * match.
 */
class ExactMatchIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired
    EngineCalls engine;

    @Test
    void onlyPay101MatchesInTheWorkedExample() {
        var f = new ReconFixture(jdbc);
        UUID l101 = f.ledger("PAY-101", "1000.00", "AED", DAY);
        UUID g101 = f.gateway("PAY-101", "1000.00", "AED", DAY);
        f.ledger("PAY-102", "500.00", "AED", DAY);
        f.ledger("PAY-103", "750.00", "AED", DAY);
        f.gateway("PAY-103", "700.00", "AED", DAY);
        f.gateway("PAY-104", "200.00", "AED", DAY);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        DayResult result = engine.reconcileDay(run, DAY);

        assertThat(result.matchesInserted()).isEqualTo(1);
        assertThat(matches(run.runId())).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("ledger_record_id", l101).containsEntry("gateway_record_id", g101);
            assertThat(m).containsEntry("match_type", "EXACT").containsEntry("organization_id", f.org);
            assertThat(((java.math.BigDecimal) m.get("amount_difference")).signum())
                    .isZero();
        });
        assertThat(result.discrepancies())
                .extracting(Discrepancy::discrepancyKey)
                .containsExactly(
                        "AMOUNT_MISMATCH:PAY-103:PAYMENT:AED",
                        "MISSING_IN_GATEWAY:PAY-102:PAYMENT:AED",
                        "MISSING_IN_LEDGER:PAY-104:PAYMENT:AED");
    }

    @Test
    void theTransactionTypeIsPartOfTheKey() {
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", DAY);
        f.record(SourceType.GATEWAY, "PAY-1", "10.00", "AED", DAY, "SUCCESS", "REFUND", false);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        DayResult result = engine.reconcileDay(run, DAY);

        assertThat(result.matchesInserted()).isZero();
        assertThat(result.discrepancies())
                .extracting(Discrepancy::discrepancyKey)
                .containsExactly("MISSING_IN_GATEWAY:PAY-1:PAYMENT:AED", "MISSING_IN_LEDGER:PAY-1:REFUND:AED");
    }

    @Test
    void reExecutingADayAddsNoSecondMatch() {
        // The checkpoint normally prevents this; ON CONFLICT DO NOTHING is the safety net.
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", DAY);
        f.gateway("PAY-1", "10.00", "AED", DAY);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        assertThat(engine.reconcileDay(run, DAY).matchesInserted()).isEqualTo(1);
        assertThat(engine.reconcileDay(run, DAY).matchesInserted()).isZero();
        assertThat(matches(run.runId())).hasSize(1);
    }

    List<Map<String, Object>> matches(UUID run) {
        return jdbc.queryForList("SELECT * FROM reconciliation_match WHERE run_id = ?", run);
    }
}
