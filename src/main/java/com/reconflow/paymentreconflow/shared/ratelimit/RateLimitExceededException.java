package com.reconflow.paymentreconflow.shared.ratelimit;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * {@code 429 rate-limited} with a {@code Retry-After} (added by the global handler). Without it,
 * well-behaved clients retry at once and make the overload worse.
 */
public class RateLimitExceededException extends ApiException {

    private final Duration retryAfter;

    public RateLimitExceededException(String detail, Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, ProblemCodes.RATE_LIMITED, detail, Map.of());
        this.retryAfter = retryAfter;
    }

    /** Whole seconds, rounded up and at least 1: {@code Retry-After: 0} invites an immediate retry. */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
