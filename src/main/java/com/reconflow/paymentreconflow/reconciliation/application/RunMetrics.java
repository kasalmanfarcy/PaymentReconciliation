package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.reconciliation.domain.RunSummary;
import com.reconflow.paymentreconflow.shared.jobs.AfterCommit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Reconciliation metrics (M9.9); fixed, low-cardinality tags only (no run or organization id). */
@Component
class RunMetrics {

    private final MeterRegistry registry;

    RunMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void completed(RunSummary s, Duration took) {
        AfterCommit.run(() -> {
            timer("completed").record(took);
            result("matched", s.matchedCount());
            result("mismatch", s.mismatchCount());
            result("unmatched_left", s.unmatchedLeftCount());
            result("unmatched_right", s.unmatchedRightCount());
            result("duplicate", s.duplicateCount());
            result("currency_mismatch", s.currencyMismatchCount());
        });
    }

    void failed(Duration took) {
        AfterCommit.run(() -> timer("failed").record(took));
    }

    private Timer timer(String outcome) {
        return Timer.builder("reconflow.reconciliation.duration")
                .description("Reconciliation run time, from start to a final state")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry);
    }

    private void result(String result, int count) {
        registry.counter("reconflow.reconciliation.results", "result", result).increment(count);
    }
}
