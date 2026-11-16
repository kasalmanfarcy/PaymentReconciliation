package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M6.22, the engine half: ledger 1000.0 and gateway 1000.00 are an EXACT match, never an
 * AMOUNT_MISMATCH. The amounts are inserted at different scales on purpose: NUMERIC compares by
 * value in SQL whatever the scale. (Sample PAY-105, 300.00 vs 300.0, is the same case end to end.)
 */
class MoneyEqualityIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired
    RunDriver driver;

    @Test
    void onePointZeroAndOnePointZeroZeroMatch() {
        var f = new ReconFixture(jdbc);
        UUID l = f.ledger("PAY-1", "1000.0", "AED", DAY);
        UUID g = f.gateway("PAY-1", "1000.00", "AED", DAY);
        jdbc.update("UPDATE transaction_record SET amount = 1000.0 WHERE id = ?", l);

        UUID run = driver.run(f, DAY, DAY, "AED");

        assertThat(driver.matchedPairs(run)).containsExactly(l + "/" + g);
        assertThat(driver.summary(run))
                .containsEntry("matched_count", 1)
                .containsEntry("matched_amount", "1000.0000")
                .containsEntry("mismatch_count", 0);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM exception_case WHERE organization_id = ?", Long.class, f.org))
                .isZero();
    }
}
