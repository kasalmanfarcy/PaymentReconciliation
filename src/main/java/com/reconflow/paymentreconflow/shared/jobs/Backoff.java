package com.reconflow.paymentreconflow.shared.jobs;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Retry delay after a transient failure: {@code base × 2^attempts}, capped, ±20 % jitter. With the
 * 10 s base that is ADR 0004's {@code 2^attempts × 10 s}: 20 s after the first attempt, 320 s after
 * the fifth. (M5 built {@code 2^(attempts-1)}, half that; M9.2 brought it in line with the docs.)
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
        int exponent = Math.min(Math.max(attempts, 0), 30); // 2^30 × base already dwarfs any cap
        long millis = Math.min(base.toMillis() * (1L << exponent), max.toMillis());
        double factor = 1 + (random.nextDouble() * 2 - 1) * JITTER;
        return Duration.ofMillis(Math.round(millis * factor));
    }
}
