package com.reconflow.paymentreconflow.shared.jobs;

import java.util.UUID;

/** This worker no longer owns the job; another one does. Stop and change nothing. */
public class LeaseLostException extends RuntimeException {

    public LeaseLostException(UUID jobId, String workerId) {
        super("Job " + jobId + " is no longer leased to " + workerId);
    }
}
