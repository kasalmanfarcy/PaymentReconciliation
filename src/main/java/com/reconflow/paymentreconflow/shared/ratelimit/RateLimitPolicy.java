package com.reconflow.paymentreconflow.shared.ratelimit;

/** Named limits; their capacities live in {@link RateLimitProperties}, not in code. */
public enum RateLimitPolicy {
    /** {@code POST /imports} and {@code POST /imports/{id}/retry}. */
    IMPORT_UPLOAD,
    /** {@code POST /reconciliation-runs} and {@code POST /reconciliation-runs/{id}/retry}. */
    RUN_CREATE
}
