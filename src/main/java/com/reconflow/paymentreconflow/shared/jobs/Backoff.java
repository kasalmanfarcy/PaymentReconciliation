package com.reconflow.paymentreconflow.shared.jobs;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Retry delay after a transient failure: {@code base × 2^(attempts-1)}, capped, ±20 % jitter.
 *
 * <p>The jitter matters: without it, every job that failed during a 30-second database outage
 * retries at the same instant when the database comes back and knocks it over again (a thundering
 * herd). Spreading them out by a random fifth is enough.
 */
final class Backoff {

    static final double JITTER = 0.2;

    private Backoff() {}

    /** @param attempts attempts made so far, including the one that just failed (≥ 1) */
    static Duration delay(int attempts, Duration base, Duration max, RandomGenerator random) {
        int exponent = Math.min(Math.max(attempts - 1, 0), 30); // 2^30 × base overflows nothing useful
        long millis = Math.min(base.toMillis() * (1L << exponent), max.toMillis());
        double factor = 1 + (random.nextDouble() * 2 - 1) * JITTER;
        return Duration.ofMillis(Math.round(millis * factor));
    }
}
