package com.reconflow.paymentreconflow.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class BackoffTest {

    static final Duration BASE = Duration.ofSeconds(10);
    static final Duration MAX = Duration.ofMinutes(10);

    @Test
    void doublesPerAttemptFromTheBase() {
        RandomGenerator middle = new FixedDouble(0.5);
        assertThat(Backoff.delay(1, BASE, MAX, middle)).isEqualTo(Duration.ofSeconds(10));
        assertThat(Backoff.delay(2, BASE, MAX, middle)).isEqualTo(Duration.ofSeconds(20));
        assertThat(Backoff.delay(3, BASE, MAX, middle)).isEqualTo(Duration.ofSeconds(40));
        assertThat(Backoff.delay(4, BASE, MAX, middle)).isEqualTo(Duration.ofSeconds(80));
    }

    @Test
    void isCappedAtTheMaximum() {
        assertThat(Backoff.delay(30, BASE, MAX, new FixedDouble(0.5))).isEqualTo(MAX);
        assertThat(Backoff.delay(Integer.MAX_VALUE, BASE, MAX, new FixedDouble(0.5)))
                .isEqualTo(MAX);
    }

    @Test
    void jitterStaysWithinTwentyPercent() {
        assertThat(Backoff.delay(1, BASE, MAX, new FixedDouble(0.0))).isEqualTo(Duration.ofSeconds(8));
        assertThat(Backoff.delay(1, BASE, MAX, new FixedDouble(0.999999)))
                .isBetween(Duration.ofMillis(11_999), Duration.ofSeconds(12));
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
