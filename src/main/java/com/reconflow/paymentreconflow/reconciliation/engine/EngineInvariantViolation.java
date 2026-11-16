package com.reconflow.paymentreconflow.reconciliation.engine;

import com.reconflow.paymentreconflow.shared.jobs.PermanentJobFailure;

/**
 * The engine found data that its rules say can't exist. Permanent: a retry would hit it again, so
 * the job fails at once and the run is marked FAILED with a short, user-safe reason.
 */
public class EngineInvariantViolation extends PermanentJobFailure {

    public EngineInvariantViolation(String detail) {
        super("engine-invariant-violated: " + detail);
    }
}
