package com.reconflow.paymentreconflow.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class BackoffTest {

    static final Duration BASE = Duration.ofSeconds(10);
    static final Duration MAX = Duration.ofHours(1);

    /** ADR 0004: {@code 2^attempts × 10 s}. */
    @Test
    void isTwoToTheAttemptsTimesTheBase() {
        RandomGenerator middle = new FixedDouble(0.5);
        assertThat(Backoff.delay(1, BASE, MAX, middle)).isEqualTo(Duration.ofSeconds(20));
        assertThat(Backoff.delay(2, BASE, MAX, middle)).isEqualTo(Duration.ofSeconds(40));
        assertThat(Backoff.delay(3, BASE, MAX, middle)).isEqualTo(Duration.ofSeconds(80));
        assertThat(Backoff.delay(5, BASE, MAX, middle)).isEqualTo(Duration.ofSeconds(320));
        assertThat(Backoff.delay(8, BASE, MAX, middle)).isEqualTo(Duration.ofSeconds(2560));
    }

    @Test
    void isCappedAtTheMaximum() {
        assertThat(Backoff.delay(30, BASE, MAX, new FixedDouble(0.5))).isEqualTo(MAX);
        assertThat(Backoff.delay(Integer.MAX_VALUE, BASE, MAX, new FixedDouble(0.5)))
                .isEqualTo(MAX);
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        assertThat(Backoff.delay(1, BASE, MAX, new FixedDouble(0.0))).isEqualTo(Duration.ofSeconds(16));
        assertThat(Backoff.delay(1, BASE, MAX, new FixedDouble(0.999999)))
                .isBetween(Duration.ofMillis(23_999), Duration.ofSeconds(24));
        assertThat(Backoff.delay(5, BASE, MAX, new FixedDouble(0.0))).isEqualTo(Duration.ofSeconds(256));
    }

    private record FixedDouble(double value) implements RandomGenerator {
        @Override
        public long nextLong() {
            return 0;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }
}
