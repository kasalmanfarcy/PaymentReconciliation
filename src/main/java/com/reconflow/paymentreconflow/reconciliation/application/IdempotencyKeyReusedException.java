package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * {@code 422 idempotency-key-reused}: this {@code Idempotency-Key} already names a run with a
 * different request (period, sources or currency). Borrowed from the IETF Idempotency-Key draft;
 * the request is well-formed but conflicts with what the key already means.
 */
public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException() {
        super(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ProblemCodes.IDEMPOTENCY_KEY_REUSED,
                "This Idempotency-Key was already used for a different reconciliation request.",
                Map.of());
    }
}
