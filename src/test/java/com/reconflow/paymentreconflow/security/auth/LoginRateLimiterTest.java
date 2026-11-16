package com.reconflow.paymentreconflow.security.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LoginRateLimiterTest {

    private final AtomicLong now = new AtomicLong();
    private final LoginRateLimiter limiter = new LoginRateLimiter(10, 10, now::get);

    @Test
    void allowsABurstOfTenThenRefusesTheEleventh() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("198.51.100.1").allowed()).isTrue();
        }
        LoginRateLimiter.Decision eleventh = limiter.tryAcquire("198.51.100.1");

        assertThat(eleventh.allowed()).isFalse();
        assertThat(eleventh.retryAfterSeconds()).isBetween(1L, 7L); // one token per 6 s
    }

    @Test
    void bucketsArePerClientIp() {
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("198.51.100.1");
        }

        assertThat(limiter.tryAcquire("198.51.100.2").allowed()).isTrue();
    }

    @Test
    void tokensRefillOverTime() {
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("198.51.100.1");
        }
        now.addAndGet(TimeUnit.SECONDS.toNanos(6)); // 10 per minute: one token every 6 s

        assertThat(limiter.tryAcquire("198.51.100.1").allowed()).isTrue();
        assertThat(limiter.tryAcquire("198.51.100.1").allowed()).isFalse();
    }
}
