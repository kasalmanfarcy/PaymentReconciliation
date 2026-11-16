package com.reconflow.paymentreconflow.shared.jobs;

/** Thrown by a handler that saw {@link JobContext#stopRequested()} at a chunk boundary. */
public class JobStoppedException extends RuntimeException {

    public JobStoppedException() {
        super("worker shutting down");
    }
}
