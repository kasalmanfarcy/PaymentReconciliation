package com.reconflow.paymentreconflow.shared.jobs;

import java.time.Duration;

/**
 * Thrown by a handler that can't start <em>yet</em> and shouldn't spend an attempt waiting, e.g. a
 * reconciliation run while an import of its organization is still processing (phase-M6 §M6.7). The
 * queue puts the job back with {@code run_after = now() + delay}, attempts unchanged. Unlike a
 * transient failure, nothing went wrong, so it must never exhaust {@code max_attempts}.
 */
public class JobDeferredException extends RuntimeException {

    private final Duration delay;

    public JobDeferredException(Duration delay, String reason) {
        super(reason);
        this.delay = delay;
    }

    public Duration delay() {
        return delay;
    }
}
