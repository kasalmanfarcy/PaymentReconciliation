package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.exceptions.Discrepancy;
import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.RecordRef;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.engine.RunContext;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M6.7: unmatched records on both sides (rule O5), named by source, with reason code
 * COUNTERPART_NOT_SUCCESS when the other source has the reference but not as SUCCESS.
 */
class MissingDetectionIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired
    EngineCalls engine;

    @Test
    void ledgerOnlyAndGatewayOnlyRecordsAreMissingOnTheOtherSide() {
        var f = new ReconFixture(jdbc);
        UUID pay102 = f.ledger("PAY-102", "500.00", "AED", DAY);
        UUID pay104 = f.gateway("PAY-104", "200.00", "AED", DAY.plusDays(1));
        RunContext run = engine.context(f, DAY, DAY.plusDays(1), "AED");

        List<Discrepancy> found = List.of(
                engine.reconcileDay(run, DAY).discrepancies().getFirst(),
                engine.reconcileDay(run, DAY.plusDays(1)).discrepancies().getFirst());

        assertThat(found.get(0).type()).isEqualTo(ExceptionType.MISSING_IN_GATEWAY);
        assertThat(found.get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(found.get(0).amountDifference()).isNull();
        assertThat(found.get(0).reasonCode()).isNull();
        assertThat(found.get(0).records()).containsExactly(new RecordRef(pay102, DAY));
        assertThat(found.get(1).type()).isEqualTo(ExceptionType.MISSING_IN_LEDGER);
        assertThat(found.get(1).amount()).isEqualByComparingTo("200.00");
        assertThat(found.get(1).records()).containsExactly(new RecordRef(pay104, DAY.plusDays(1)));
    }

    @Test
    void aFailedGatewayCounterpartGivesReasonCounterpartNotSuccess() {
        var f = new ReconFixture(jdbc);
        UUID ledger = f.ledger("PAY-1", "10.00", "AED", DAY);
        f.record(SourceType.GATEWAY, "PAY-1", "10.00", "AED", DAY, "FAILED", "PAYMENT", false);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        Discrepancy d = engine.reconcileDay(run, DAY).discrepancies().getFirst();

        assertThat(d.type()).isEqualTo(ExceptionType.MISSING_IN_GATEWAY);
        assertThat(d.reasonCode()).isEqualTo("COUNTERPART_NOT_SUCCESS");
        assertThat(d.records()).containsExactly(new RecordRef(ledger, DAY)); // the FAILED record is excluded (P4)
    }

    @Test
    void aNonSuccessRecordOnTheSameSideIsNotACounterpart() {
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", DAY);
        f.record(SourceType.INTERNAL_LEDGER, "PAY-1", "10.00", "AED", DAY, "REVERSED", "PAYMENT", false);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        assertThat(engine.reconcileDay(run, DAY).discrepancies().getFirst().reasonCode())
                .isNull();
    }

    @Test
    void aPendingLedgerCounterpartMarksAGatewayOnlyRecord() {
        var f = new ReconFixture(jdbc);
        f.gateway("PAY-1", "10.00", "AED", DAY);
        f.record(SourceType.INTERNAL_LEDGER, "PAY-1", "10.00", "AED", DAY, "PENDING", "PAYMENT", false);
        RunContext run = engine.context(f, DAY, DAY, "AED");

        Discrepancy d = engine.reconcileDay(run, DAY).discrepancies().getFirst();

        assertThat(d.type()).isEqualTo(ExceptionType.MISSING_IN_LEDGER);
        assertThat(d.reasonCode()).isEqualTo("COUNTERPART_NOT_SUCCESS");
    }
}
