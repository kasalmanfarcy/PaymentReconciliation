package com.reconflow.paymentreconflow.shared.ratelimit;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Puts a controller method under a request-rate limit (M10.4), per user per organization. Only
 * endpoints that create background work carry it: they are what a client in a retry loop can use to
 * fill the queue for everyone.
 */
@Target(METHOD)
@Retention(RUNTIME)
public @interface RateLimited {
    RateLimitPolicy value();
}
