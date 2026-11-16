package com.reconflow.paymentreconflow.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Layer 1 of M10.4: a token bucket per (policy, organization, user), in memory.
 *
 * <p>Token bucket rather than a fixed window: a fixed window lets a client send twice the limit
 * across the boundary of two windows; a bucket allows a burst up to its capacity and then refills
 * continuously ("greedy"), capping the long-run rate.
 *
 * <p>Keyed by user and organization, never by IP: an office behind one NAT would share one limit
 * while a botnet gets one per address. Runs after the security filters, so the user and tenant are
 * already resolved (an unauthenticated request never gets here).
 *
 * <p>Per instance: with N API instances a user gets up to N times the rate. Accepted because layer 2
 * (the pending-job cap in {@code JobQueue}, in the database) is exact, and it protects the resource
 * that actually runs out. A shared limit would need Redis or a database bucket per request.
 */
@Component
class RateLimitInterceptor implements HandlerInterceptor {

    static final String REMAINING_HEADER = "RateLimit-Remaining";

    // ObjectProviders: interceptors are picked up by @WebMvcTest slices, which have none of these
    // beans. The slice never reaches a @RateLimited method, so it never asks for them.
    private final ObjectProvider<RateLimitProperties> props;
    private final ObjectProvider<CurrentUser> currentUser;
    private final ObjectProvider<TenantContext> tenant;
    // Bounded and expiring: an unbounded map keyed by anything a client controls is a memory leak
    // an attacker can drive. An evicted bucket simply starts full again. Built on first use.
    private volatile @Nullable Cache<String, Bucket> buckets;

    RateLimitInterceptor(
            ObjectProvider<RateLimitProperties> props,
            ObjectProvider<CurrentUser> currentUser,
            ObjectProvider<TenantContext> tenant) {
        this.props = props;
        this.currentUser = currentUser;
        this.tenant = tenant;
    }

    private Cache<String, Bucket> buckets(RateLimitProperties limits) {
        Cache<String, Bucket> cache = buckets;
        if (cache == null) {
            synchronized (this) {
                cache = buckets;
                if (cache == null) {
                    cache = Caffeine.newBuilder()
                            .maximumSize(limits.maxTrackedKeys())
                            .expireAfterAccess(Duration.ofHours(1))
                            .build();
                    buckets = cache;
                }
            }
        }
        return cache;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited limited = method.getMethodAnnotation(RateLimited.class);
        if (limited == null) {
            return true;
        }
        RateLimitPolicy policy = limited.value();
        RateLimitProperties limits = props.getObject();
        RateLimitProperties.Limit limit = limits.of(policy);
        String key = policy + ":" + tenant.getObject().organizationId() + ":"
                + currentUser.getObject().id();
        Bucket bucket = buckets(limits)
                .get(
                        key,
                        k -> Bucket.builder()
                                .addLimit(l ->
                                        l.capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()))
                                .build());

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader(REMAINING_HEADER, Long.toString(probe.getRemainingTokens()));
            return true;
        }
        response.setHeader(REMAINING_HEADER, "0");
        throw new RateLimitExceededException(
                "Too many requests: at most " + limit.capacity() + " per "
                        + limit.period().toMinutes() + " minute(s). Retry after the Retry-After interval.",
                Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
}
