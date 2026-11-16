package com.reconflow.paymentreconflow.security.auth;

import com.reconflow.paymentreconflow.security.config.AuthProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A token bucket per client IP for {@code POST /auth/login}: {@code capacity} attempts in a burst,
 * refilled at {@code refillPerMinute}. Complements the per-account lockout: lockout stops guessing
 * one account's password; the rate limit stops one client spraying guesses across many accounts
 * (which never trips any single account's lock).
 *
 * <p>In memory, per instance: two API instances allow twice the rate. Acceptable for v1; a shared
 * limit would need Redis or the database (authentication.plan §8).
 *
 * <p>The key is {@code request.getRemoteAddr()}. Behind a load balancer that's the balancer's
 * address; {@code server.forward-headers-strategy} may be turned on <em>only</em> behind a proxy
 * that overwrites X-Forwarded-For, or every client can send a fresh fake IP per request and get a
 * fresh bucket each time.
 */
@Component
public class LoginRateLimiter {

    /** Whether to let the attempt through and, if not, how long until a token is available. */
    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private static final long IDLE_EVICTION_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final int EVICT_EVERY_N_CALLS = 1_000;

    private final int capacity;
    private final double tokensPerNano;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Autowired
    LoginRateLimiter(AuthProperties properties) {
        this(properties.rateLimit().capacity(), properties.rateLimit().refillPerMinute(), System::nanoTime);
    }

    LoginRateLimiter(int capacity, int refillPerMinute, LongSupplier nanoTime) {
        this.capacity = capacity;
        this.tokensPerNano = refillPerMinute / (double) TimeUnit.MINUTES.toNanos(1);
        this.nanoTime = nanoTime;
    }

    public Decision tryAcquire(String clientIp) {
        if (calls.incrementAndGet() % EVICT_EVERY_N_CALLS == 0) {
            evictIdle();
        }
        long now = nanoTime.getAsLong();
        return buckets.computeIfAbsent(clientIp, ip -> new Bucket(capacity, now))
                .tryTake(now);
    }

    /** Memory stays bounded by recent clients, not by every IP ever seen. */
    private void evictIdle() {
        long now = nanoTime.getAsLong();
        buckets.values().removeIf(b -> b.idleSince(now) > IDLE_EVICTION_NANOS);
    }

    private final class Bucket {
        private double tokens;
        private long lastRefill;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
        }

        synchronized Decision tryTake(long now) {
            tokens = Math.min(capacity, tokens + (now - lastRefill) * tokensPerNano);
            lastRefill = now;
            if (tokens >= 1) {
                tokens -= 1;
                return new Decision(true, 0);
            }
            long nanosUntilToken = (long) Math.ceil((1 - tokens) / tokensPerNano);
            return new Decision(false, Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanosUntilToken) + 1));
        }

        synchronized long idleSince(long now) {
            return now - lastRefill;
        }
    }
}
