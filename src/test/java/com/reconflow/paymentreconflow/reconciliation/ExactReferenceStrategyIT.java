package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.exceptions.Discrepancy;
import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.engine.DayResult;
import com.reconflow.paymentreconflow.reconciliation.engine.ExactReferenceStrategy;
import com.reconflow.paymentreconflow.reconciliation.engine.MatchingStrategy;
import com.reconflow.paymentreconflow.reconciliation.engine.RunContext;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M6.4: the strategy on its own, one day at a time, inside a transaction with the tenant bound, as
 * the executor will call it. Anchor-day chunking means each group is classified exactly once, on
 * the earliest date of its participating records.
 */
class ExactReferenceStrategyIT extends AbstractIntegrationTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    static final LocalDate D2 = D1.plusDays(1);

    @Autowired
    Map<String, MatchingStrategy> strategies;

    @Autowired
    ExactReferenceStrategy strategy;

    @Autowired
    TenantScope tenantScope;

    @Autowired
    TransactionTemplate tx;

    @Test
    void exactReferenceIsTheOnlyStrategy() {
        assertThat(strategies.values()).singleElement().isInstanceOf(ExactReferenceStrategy.class);
        assertThat(strategy.name()).isEqualTo("EXACT_REFERENCE");
    }

    @Test
    void aGroupSpreadOverTwoDaysIsClassifiedOnceOnItsAnchorDay() {
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", D1);
        f.gateway("PAY-1", "12.00", "AED", D2); // an amount mismatch across days
        f.gateway("PAY-2", "5.00", "AED", D2); // gateway-only, anchored on D2
        RunContext run = context(f, D1, D2, "AED");

        DayResult day1 = reconcile(f.org, run, D1);
        DayResult day2 = reconcile(f.org, run, D2);

        assertThat(day1.discrepancies()).extracting(Discrepancy::type).containsExactly(ExceptionType.AMOUNT_MISMATCH);
        assertThat(day2.discrepancies()).extracting(Discrepancy::type).containsExactly(ExceptionType.MISSING_IN_LEDGER);
    }

    @Test
    void groupsWithNoRecordInTheRunsCurrencyBelongToAnotherRun() {
        var f = new ReconFixture(jdbc);
        f.gateway("PAY-1", "5.00", "USD", D1);

        assertThat(reconcile(f.org, context(f, D1, D1, "AED"), D1).discrepancies())
                .isEmpty();
        assertThat(reconcile(f.org, context(f, D1, D1, "USD"), D1).discrepancies())
                .singleElement()
                .extracting(Discrepancy::type)
                .isEqualTo(ExceptionType.MISSING_IN_LEDGER);
    }

    @Test
    void onlyRecordsOfAcceptedBatchesTakePart() {
        // Rule P3: rows of a batch still PROCESSING, or one that FAILED, aren't accepted records yet.
        var f = new ReconFixture(jdbc);
        f.ledger("PAY-1", "10.00", "AED", D1);
        UUID processing = f.batch("GATEWAY", "PROCESSING");
        f.recordInBatch(processing, SourceType.GATEWAY, "PAY-1", "10.00", "AED", D1, "SUCCESS", "PAYMENT", false);

        assertThat(reconcile(f.org, context(f, D1, D1, "AED"), D1).discrepancies())
                .singleElement()
                .extracting(Discrepancy::type)
                .isEqualTo(ExceptionType.MISSING_IN_GATEWAY);
    }

    @Test
    void anotherOrganizationsRecordsNeverTakePart() {
        var a = new ReconFixture(jdbc);
        var b = new ReconFixture(jdbc);
        a.ledger("PAY-1", "10.00", "AED", D1);
        b.gateway("PAY-1", "10.00", "AED", D1);

        List<Discrepancy> found =
                reconcile(a.org, context(a, D1, D1, "AED"), D1).discrepancies();

        assertThat(found).singleElement().satisfies(d -> {
            assertThat(d.type()).isEqualTo(ExceptionType.MISSING_IN_GATEWAY);
            assertThat(d.records()).hasSize(1);
        });
    }

    RunContext context(ReconFixture f, LocalDate from, LocalDate to, String currency) {
        return new RunContext(
                f.org,
                f.pendingRun(from, to, currency),
                from,
                to,
                SourceType.INTERNAL_LEDGER,
                SourceType.GATEWAY,
                currency);
    }

    DayResult reconcile(UUID org, RunContext run, LocalDate day) {
        return tenantScope.call(org, () -> tx.execute(s -> strategy.reconcileDay(run, day)));
    }
}
