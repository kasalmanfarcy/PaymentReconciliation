package com.reconflow.paymentreconflow.reconciliation;

import com.reconflow.paymentreconflow.exceptions.DiscrepancyRecorder;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.engine.DayResult;
import com.reconflow.paymentreconflow.reconciliation.engine.MatchingStrategy;
import com.reconflow.paymentreconflow.reconciliation.engine.RunContext;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs the strategy for one day as the executor does: tenant bound, inside a transaction. */
@Component
class EngineCalls {

    private final MatchingStrategy strategy;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;
    private final DiscrepancyRecorder recorder;

    EngineCalls(
            MatchingStrategy strategy, TenantScope tenantScope, TransactionTemplate tx, DiscrepancyRecorder recorder) {
        this.strategy = strategy;
        this.recorder = recorder;
        this.tenantScope = tenantScope;
        this.tx = tx;
    }

    RunContext context(ReconFixture f, LocalDate from, LocalDate to, String currency) {
        UUID run = f.pendingRun(from, to, currency);
        return new RunContext(f.org, run, from, to, SourceType.INTERNAL_LEDGER, SourceType.GATEWAY, currency);
    }

    DayResult reconcileDay(RunContext run, LocalDate day) {
        return tenantScope.call(run.organizationId(), () -> tx.execute(s -> strategy.reconcileDay(run, day)));
    }

    /** The strategy and the exceptions module in one transaction, as a day-chunk does. */
    DayResult reconcileAndRecord(RunContext run, LocalDate day) {
        return tenantScope.call(
                run.organizationId(),
                () -> tx.execute(s -> {
                    DayResult result = strategy.reconcileDay(run, day);
                    recorder.record(run.organizationId(), run.runId(), result.discrepancies());
                    return result;
                }));
    }
}
