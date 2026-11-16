package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.exceptions.Discrepancy;
import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.RecordRef;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.engine.DayResult;
import com.reconflow.paymentreconflow.reconciliation.engine.RunContext;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M6.8: more than one candidate on either side, or a record flagged as a within-file duplicate at
 * import (D1), is one DUPLICATE_IN_SOURCE case linking every record of the group, both sides.
 * Never an arbitrary pick: "take the first" could hide a double charge.
 */
class DuplicateDetectionIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired
    EngineCalls engine;

    @Test
    void txn104IsOneDuplicateCaseLinkingAllThreeRecordsAndNoMatch() {
        var f = new ReconFixture(jdbc);
        UUID ledger = f.ledger("TXN-104", "250.00", "INR", DAY);
        UUID g1 = f.gateway("TXN-104", "250.00", "INR", DAY);
        UUID g2 = f.gateway("TXN-104", "250.00", "INR", DAY);
        RunContext run = engine.context(f, DAY, DAY, "INR");

        DayResult result = engine.reconcileAndRecord(run, DAY);

        assertThat(result.matchesInserted()).isZero();
        Discrepancy d = result.discrepancies().getFirst();
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(d.type()).isEqualTo(ExceptionType.DUPLICATE_IN_SOURCE);
        assertThat(d.discrepancyKey()).isEqualTo("DUPLICATE_IN_SOURCE:TXN-104:PAYMENT:INR");
        assertThat(d.reasonCode()).isEqualTo("SAME_AMOUNT");
        assertThat(d.amount()).isEqualByComparingTo("250.00");
        assertThat(d.amountDifference()).isNull();
        assertThat(d.records().getFirst()).isEqualTo(new RecordRef(ledger, DAY)); // ledger first
        assertThat(d.records()).extracting(RecordRef::id).containsExactlyInAnyOrder(ledger, g1, g2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM exception_case_record r JOIN exception_case c ON c.id = r.exception_id"
                                + " WHERE c.organization_id = ?",
                        Long.class,
                        f.org))
                .isEqualTo(3);
    }

    @Test
    void differentAmountsInOneGroupAreStillADuplicateWithTheSmallestGatewayAmount() {
        // Sample PAY-302: not flagged at import (different amounts), but the engine counts candidates (D2).
        var f = new ReconFixture(jdbc);
        f.gateway("PAY-302", "65.00", "AED", DAY);
        f.gateway("PAY-302", "60.00", "AED", DAY);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        Discrepancy d = engine.reconcileDay(run, DAY).discrepancies().getFirst();

        assertThat(d.type()).isEqualTo(ExceptionType.DUPLICATE_IN_SOURCE);
        assertThat(d.reasonCode()).isEqualTo("DIFFERENT_AMOUNTS");
        assertThat(d.amount()).isEqualByComparingTo("60.00");
    }

    @Test
    void duplicatesSpreadOverSeveralDaysAreOneCase() {
        // Two ledger records on different days, one gateway record: one case with 3 records, raised
        // once on the anchor day, not once per day.
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", DAY);
        f.ledger("PAY-1", "10.00", "AED", DAY.plusDays(2));
        f.gateway("PAY-1", "10.00", "AED", DAY.plusDays(1));
        RunContext run = engine.context(f, DAY, DAY.plusDays(2), "AED");

        DayResult d1 = engine.reconcileDay(run, DAY);
        DayResult d2 = engine.reconcileDay(run, DAY.plusDays(1));
        DayResult d3 = engine.reconcileDay(run, DAY.plusDays(2));

        assertThat(d1.discrepancies()).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(ExceptionType.DUPLICATE_IN_SOURCE);
            assertThat(d.records()).hasSize(3);
        });
        assertThat(d2.discrepancies()).isEmpty();
        assertThat(d3.discrepancies()).isEmpty();
        assertThat(d1.matchesInserted() + d2.matchesInserted() + d3.matchesInserted())
                .isZero();
    }

    @Test
    void anImportFlagMakesAOneToOneGroupADuplicate() {
        // D1's flag travels with the record: its twin may be outside this run's period.
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", DAY);
        f.record(SourceType.GATEWAY, "PAY-1", "10.00", "AED", DAY, "SUCCESS", "PAYMENT", true);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        DayResult result = engine.reconcileDay(run, DAY);

        assertThat(result.matchesInserted()).isZero();
        assertThat(result.discrepancies())
                .singleElement()
                .extracting(Discrepancy::type)
                .isEqualTo(ExceptionType.DUPLICATE_IN_SOURCE);
    }

    @Test
    void duplicatesWinOverACurrencyMismatch() {
        // Rule order O1 before O2.
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", DAY);
        f.gateway("PAY-1", "10.00", "USD", DAY);
        f.gateway("PAY-1", "10.00", "USD", DAY);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        Discrepancy d = engine.reconcileDay(run, DAY).discrepancies().getFirst();

        assertThat(d.discrepancyKey()).isEqualTo("DUPLICATE_IN_SOURCE:PAY-1:PAYMENT:AED/USD");
        assertThat(d.records()).hasSize(3);
    }
}
