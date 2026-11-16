package com.reconflow.paymentreconflow.reconciliation;

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

    EngineCalls(MatchingStrategy strategy, TenantScope tenantScope, TransactionTemplate tx) {
        this.strategy = strategy;
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
}
