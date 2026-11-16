package com.reconflow.paymentreconflow.shared.jobs;

/**
 * A failure a later attempt may get past (storage 503, a dropped connection): the job is re-queued
 * with exponential backoff, until {@code max_attempts}.
 */
public class TransientJobFailure extends RuntimeException {

    public TransientJobFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
