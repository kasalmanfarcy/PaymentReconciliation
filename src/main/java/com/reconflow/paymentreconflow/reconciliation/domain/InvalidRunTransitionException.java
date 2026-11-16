package com.reconflow.paymentreconflow.reconciliation.domain;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * {@code 409 invalid-transition} with {@code currentStatus} (api-design.md §3.1): the run state
 * machine doesn't allow this from where the run is, e.g. retrying a run that isn't FAILED.
 */
public class InvalidRunTransitionException extends ApiException {

    public InvalidRunTransitionException(UUID runId, RunStatus current, RunStatus requested) {
        super(
                HttpStatus.CONFLICT,
                ProblemCodes.INVALID_TRANSITION,
                "Reconciliation run " + runId + " is " + current + " and can't move to " + requested + ".",
                Map.of("currentStatus", current.name()));
    }
}
