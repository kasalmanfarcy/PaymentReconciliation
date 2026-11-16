package com.reconflow.paymentreconflow.shared.jobs;

/**
 * A failure that would happen again on every attempt (unreadable file, too many rows): the job
 * fails at once and its handler's {@code onPermanentFailure} receives the message as the reason, so
 * the message must be safe to show users: a short code, no internals.
 */
public class PermanentJobFailure extends RuntimeException {

    public PermanentJobFailure(String reason) {
        super(reason);
    }

    public PermanentJobFailure(String reason, Throwable cause) {
        super(reason, cause);
    }
}
