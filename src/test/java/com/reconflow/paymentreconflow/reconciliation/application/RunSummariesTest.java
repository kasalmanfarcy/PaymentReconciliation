package com.reconflow.paymentreconflow.reconciliation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.RunExceptionSummary;
import com.reconflow.paymentreconflow.exceptions.RunExceptionSummary.Totals;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.domain.RunSummary;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Case types are named by source, summary columns by side: the mapping follows the left source. */
class RunSummariesTest {

    static final RunExceptionSummary SEEN = new RunExceptionSummary(Map.of(
            ExceptionType.MISSING_IN_GATEWAY, new Totals(1, new BigDecimal("500.0000"), BigDecimal.ZERO),
            ExceptionType.MISSING_IN_LEDGER, new Totals(2, new BigDecimal("245.0000"), BigDecimal.ZERO),
            ExceptionType.AMOUNT_MISMATCH, new Totals(1, new BigDecimal("750.0000"), new BigDecimal("-50.0000")),
            ExceptionType.DUPLICATE_IN_SOURCE, new Totals(3, new BigDecimal("1.0000"), BigDecimal.ZERO)));

    @Test
    void withTheDefaultSourcesLeftIsTheLedger() {
        RunSummary s = RunSummaries.of(SourceType.INTERNAL_LEDGER, 3, new BigDecimal("1420.5000"), SEEN, 1);

        assertThat(s.unmatchedLeftCount()).isEqualTo(1);
        assertThat(s.unmatchedLeftAmount()).isEqualByComparingTo("500");
        assertThat(s.unmatchedRightCount()).isEqualTo(2);
        assertThat(s.unmatchedRightAmount()).isEqualByComparingTo("245");
        assertThat(s.mismatchCount()).isEqualTo(1);
        assertThat(s.mismatchNetDifference()).isEqualByComparingTo("-50");
        assertThat(s.duplicateCount()).isEqualTo(3);
        assertThat(s.currencyMismatchCount()).isZero();
        assertThat(s.matchedAmount()).isEqualByComparingTo("1420.50");
        assertThat(s.excludedCount()).isEqualTo(1);
    }

    @Test
    void withTheGatewayOnTheLeftTheSidesSwap() {
        RunSummary s = RunSummaries.of(SourceType.GATEWAY, 0, BigDecimal.ZERO, SEEN, 0);

        assertThat(s.unmatchedLeftCount()).isEqualTo(2);
        assertThat(s.unmatchedRightCount()).isEqualTo(1);
    }

    @Test
    void typesTheRunNeverSawAreZero() {
        RunSummary s =
                RunSummaries.of(SourceType.INTERNAL_LEDGER, 0, BigDecimal.ZERO, new RunExceptionSummary(Map.of()), 0);

        assertThat(s.unmatchedLeftAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(s.mismatchNetDifference()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
