package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.shared.jobs.LeaseLostException;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The run isn't where this worker expects it: another worker has moved it on (a zombie woke up
 * after its lease expired), or it is no longer RUNNING. The checkpoint acts as a fencing token. A
 * {@link LeaseLostException}, so the queue treats it the same way: stop, change nothing, never fail
 * the run that someone else is processing.
 */
public class StaleRunOwnershipException extends LeaseLostException {

    public StaleRunOwnershipException(UUID runId, LocalDate day, String status, @Nullable LocalDate checkpoint) {
        super("Run " + runId + " is " + status + " with checkpoint " + checkpoint + "; this worker expected to do "
                + day);
    }
}
