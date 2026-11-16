package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M6.18: the documented examples <em>are</em> the specification. Each test inserts the example's
 * records into a fresh organization, runs the real job, and asserts the outcome of every record,
 * the cases' amounts and keys, the summary and the invariant: not just the counts.
 */
class WorkedExamplesIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired
    RunDriver driver;

    @Autowired
    RunOutcomes outcomes;

    /** README / projectoverview.md §1 and reconciliation-rules.md §9: TXN-101..105, INR, one day. */
    @Test
    void example1Txn101To105() {
        var f = new ReconFixture(jdbc);
        UUID l101 = f.ledger("TXN-101", "1000.00", "INR", DAY);
        UUID g101 = f.gateway("TXN-101", "1000.00", "INR", DAY);
        UUID l102 = f.ledger("TXN-102", "500.00", "INR", DAY);
        UUID l103 = f.ledger("TXN-103", "750.00", "INR", DAY);
        UUID g103 = f.gateway("TXN-103", "700.00", "INR", DAY);
        UUID l104 = f.ledger("TXN-104", "250.00", "INR", DAY);
        UUID g104a = f.gateway("TXN-104", "250.00", "INR", DAY);
        UUID g104b = f.gateway("TXN-104", "250.00", "INR", DAY);
        UUID l105 = f.ledger("TXN-105", "300.00", "INR", DAY);
        UUID g105 = f.gateway("TXN-105", "300.00", "INR", DAY);

        UUID run = driver.run(f, DAY, DAY, "INR");

        assertThat(outcomes.byRecord(run))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        l101,
                        "MATCHED",
                        g101,
                        "MATCHED",
                        l102,
                        "MISSING_IN_GATEWAY",
                        l103,
                        "AMOUNT_MISMATCH",
                        g103,
                        "AMOUNT_MISMATCH",
                        l104,
                        "DUPLICATE_IN_SOURCE",
                        g104a,
                        "DUPLICATE_IN_SOURCE",
                        g104b,
                        "DUPLICATE_IN_SOURCE",
                        l105,
                        "MATCHED",
                        g105,
                        "MATCHED"));
        assertThat(driver.matchedPairs(run)).containsExactlyInAnyOrder(l101 + "/" + g101, l105 + "/" + g105);
        assertCase(f.org, "MISSING_IN_GATEWAY:TXN-102:PAYMENT:INR", "500.0000", null, null, 1);
        assertCase(f.org, "AMOUNT_MISMATCH:TXN-103:PAYMENT:INR", "750.0000", "-50.0000", null, 2);
        assertCase(f.org, "DUPLICATE_IN_SOURCE:TXN-104:PAYMENT:INR", "250.0000", null, "SAME_AMOUNT", 3);
        assertThat(driver.summary(run))
                .containsEntry("matched_count", 2)
                .containsEntry("matched_amount", "1300.0000")
                .containsEntry("mismatch_count", 1)
                .containsEntry("mismatch_net_difference", "-50.0000")
                .containsEntry("unmatched_left_count", 1)
                .containsEntry("unmatched_left_amount", "500.0000")
                .containsEntry("unmatched_right_count", 0)
                .containsEntry("unmatched_right_amount", "0.0000")
                .containsEntry("duplicate_count", 1)
                .containsEntry("currency_mismatch_count", 0)
                .containsEntry("excluded_count", 0);
        outcomes.assertInvariant(run);
    }

    /** projectoverview.md §5: PAY-101..104, AED. */
    @Test
    void example2Pay101To104() {
        var f = new ReconFixture(jdbc);
        UUID l101 = f.ledger("PAY-101", "1000.00", "AED", DAY);
        UUID g101 = f.gateway("PAY-101", "1000.00", "AED", DAY);
        UUID l102 = f.ledger("PAY-102", "500.00", "AED", DAY);
        UUID l103 = f.ledger("PAY-103", "750.00", "AED", DAY);
        UUID g103 = f.gateway("PAY-103", "700.00", "AED", DAY);
        UUID g104 = f.gateway("PAY-104", "200.00", "AED", DAY);

        UUID run = driver.run(f, DAY, DAY, "AED");

        assertThat(outcomes.byRecord(run))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        l101,
                        "MATCHED",
                        g101,
                        "MATCHED",
                        l102,
                        "MISSING_IN_GATEWAY",
                        l103,
                        "AMOUNT_MISMATCH",
                        g103,
                        "AMOUNT_MISMATCH",
                        g104,
                        "MISSING_IN_LEDGER"));
        assertCase(f.org, "MISSING_IN_GATEWAY:PAY-102:PAYMENT:AED", "500.0000", null, null, 1);
        assertCase(f.org, "AMOUNT_MISMATCH:PAY-103:PAYMENT:AED", "750.0000", "-50.0000", null, 2);
        assertCase(f.org, "MISSING_IN_LEDGER:PAY-104:PAYMENT:AED", "200.0000", null, null, 1);
        assertThat(driver.summary(run))
                .containsEntry("matched_count", 1)
                .containsEntry("matched_amount", "1000.0000")
                .containsEntry("mismatch_count", 1)
                .containsEntry("mismatch_net_difference", "-50.0000")
                .containsEntry("unmatched_left_count", 1)
                .containsEntry("unmatched_left_amount", "500.0000")
                .containsEntry("unmatched_right_count", 1)
                .containsEntry("unmatched_right_amount", "200.0000")
                .containsEntry("duplicate_count", 0);
        outcomes.assertInvariant(run);
    }

    @Test
    void aMatchAcrossDaysInsideThePeriod() {
        var f = new ReconFixture(jdbc);
        UUID l = f.ledger("PAY-1", "10.00", "AED", LocalDate.of(2026, 1, 31));
        UUID g = f.gateway("PAY-1", "10.00", "AED", LocalDate.of(2026, 2, 1));

        UUID run = driver.run(f, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), "AED");

        assertThat(outcomes.byRecord(run)).containsExactlyInAnyOrderEntriesOf(Map.of(l, "MATCHED", g, "MATCHED"));
        outcomes.assertInvariant(run);
    }

    @Test
    void aCounterpartOutsideThePeriodIsMissing() {
        // The documented v1 limitation: no settlement window beyond the period.
        var f = new ReconFixture(jdbc);
        UUID l = f.ledger("PAY-1", "10.00", "AED", LocalDate.of(2026, 1, 31));
        f.gateway("PAY-1", "10.00", "AED", LocalDate.of(2026, 2, 1));

        UUID run = driver.run(f, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "AED");

        assertThat(outcomes.byRecord(run)).containsExactlyInAnyOrderEntriesOf(Map.of(l, "MISSING_IN_GATEWAY"));
        outcomes.assertInvariant(run);
    }

    @Test
    void aCurrencyMismatchIsOneCaseThatTheOtherCurrencysRunFindsToo() {
        var f = new ReconFixture(jdbc);
        UUID l = f.ledger("PAY-1", "400.00", "AED", DAY);
        UUID g = f.gateway("PAY-1", "108.92", "USD", DAY);

        UUID aed = driver.run(f, DAY, DAY, "AED");
        UUID usd = driver.run(f, DAY, DAY, "USD");

        for (UUID run : List.of(aed, usd)) {
            assertThat(outcomes.byRecord(run))
                    .containsExactlyInAnyOrderEntriesOf(Map.of(l, "CURRENCY_MISMATCH", g, "CURRENCY_MISMATCH"));
            assertThat(driver.summary(run)).containsEntry("currency_mismatch_count", 1);
            outcomes.assertInvariant(run);
        }
        assertCase(f.org, "CURRENCY_MISMATCH:PAY-1:PAYMENT:AED/USD", "400.0000", null, null, 2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM exception_case WHERE organization_id = ?", Long.class, f.org))
                .isEqualTo(1);
    }

    @Test
    void aFailedGatewayCounterpartIsMissingWithAReason() {
        var f = new ReconFixture(jdbc);
        UUID l = f.ledger("PAY-1", "10.00", "AED", DAY);
        UUID g = f.record(SourceType.GATEWAY, "PAY-1", "10.00", "AED", DAY, "FAILED", "PAYMENT", false);

        UUID run = driver.run(f, DAY, DAY, "AED");

        assertThat(outcomes.byRecord(run))
                .containsExactlyInAnyOrderEntriesOf(Map.of(l, "MISSING_IN_GATEWAY", g, "EXCLUDED"));
        assertCase(f.org, "MISSING_IN_GATEWAY:PAY-1:PAYMENT:AED", "10.0000", null, "COUNTERPART_NOT_SUCCESS", 1);
        outcomes.assertInvariant(run);
    }

    @Test
    void twoLedgerRecordsOnDifferentDaysAndOneGatewayAreOneDuplicate() {
        var f = new ReconFixture(jdbc);
        UUID l1 = f.ledger("PAY-1", "10.00", "AED", DAY);
        UUID l2 = f.ledger("PAY-1", "10.00", "AED", DAY.plusDays(3));
        UUID g = f.gateway("PAY-1", "10.00", "AED", DAY.plusDays(1));

        UUID run = driver.run(f, DAY, DAY.plusDays(5), "AED");

        assertThat(outcomes.byRecord(run))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(l1, "DUPLICATE_IN_SOURCE", l2, "DUPLICATE_IN_SOURCE", g, "DUPLICATE_IN_SOURCE"));
        assertCase(f.org, "DUPLICATE_IN_SOURCE:PAY-1:PAYMENT:AED", "10.0000", null, "SAME_AMOUNT", 3);
        outcomes.assertInvariant(run);
    }

    @Test
    void aPaymentAndARefundWithOneReferenceAreTwoGroups() {
        var f = new ReconFixture(jdbc);
        UUID lp = f.ledger("PAY-1", "10.00", "AED", DAY);
        UUID gp = f.gateway("PAY-1", "10.00", "AED", DAY);
        UUID lr = f.record(SourceType.INTERNAL_LEDGER, "PAY-1", "10.00", "AED", DAY, "SUCCESS", "REFUND", false);
        UUID gr = f.record(SourceType.GATEWAY, "PAY-1", "10.00", "AED", DAY, "SUCCESS", "REFUND", false);

        UUID run = driver.run(f, DAY, DAY, "AED");

        assertThat(driver.matchedPairs(run)).containsExactlyInAnyOrder(lp + "/" + gp, lr + "/" + gr);
        outcomes.assertInvariant(run);
    }

    @Test
    void anEmptyPeriodCompletesWithZeros() {
        var f = new ReconFixture(jdbc);

        UUID run = driver.run(f, DAY, DAY.plusDays(6), "AED");

        assertThat(driver.summary(run))
                .containsEntry("status", "COMPLETED")
                .containsEntry("matched_count", 0)
                .containsEntry("mismatch_count", 0)
                .containsEntry("unmatched_left_count", 0)
                .containsEntry("unmatched_right_count", 0)
                .containsEntry("duplicate_count", 0)
                .containsEntry("currency_mismatch_count", 0)
                .containsEntry("excluded_count", 0);
        outcomes.assertInvariant(run);
    }

    private void assertCase(
            UUID org, String key, String amount, String difference, String reasonCode, int linkedRecords) {
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT c.*, (SELECT count(*) FROM exception_case_record r WHERE r.exception_id = c.id) AS records"
                        + " FROM exception_case c WHERE c.organization_id = ? AND c.discrepancy_key = ?",
                org,
                key);
        assertThat(((BigDecimal) c.get("amount")).toPlainString())
                .as(key + " amount")
                .isEqualTo(amount);
        BigDecimal diff = (BigDecimal) c.get("amount_difference");
        assertThat(diff == null ? null : diff.toPlainString())
                .as(key + " difference")
                .isEqualTo(difference);
        assertThat(c.get("reason_code")).as(key + " reason").isEqualTo(reasonCode);
        assertThat(c.get("records")).as(key + " records").isEqualTo((long) linkedRecords);
        assertThat(c.get("status")).isEqualTo("OPEN");
    }
}
