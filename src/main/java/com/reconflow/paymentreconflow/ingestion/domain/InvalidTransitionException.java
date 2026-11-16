package com.reconflow.paymentreconflow.ingestion.domain;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * {@code 409 invalid-transition} with {@code currentStatus} (api-design.md §3.1): the state machine
 * doesn't allow this from where the batch is. Covers retrying a batch that isn't FAILED.
 */
public class InvalidTransitionException extends ApiException {

    public InvalidTransitionException(UUID batchId, ImportBatchStatus current, ImportBatchStatus requested) {
        super(
                HttpStatus.CONFLICT,
                ProblemCodes.INVALID_TRANSITION,
                "Import " + batchId + " is " + current + " and can't move to " + requested + ".",
                Map.of("currentStatus", current.name()));
    }
}
