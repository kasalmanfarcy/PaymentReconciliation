package com.reconflow.paymentreconflow.exceptions.internal.domain;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * {@code 409 invalid-transition}: the request is well-formed but conflicts with the case's current
 * state (409, not 400, for that reason). The body carries {@code currentStatus} and the transitions
 * that are allowed from it, so a client can show what is possible instead.
 */
public final class InvalidTransitionException extends ApiException {

    public InvalidTransitionException(ExceptionStatus current, String detail) {
        super(
                HttpStatus.CONFLICT,
                ProblemCodes.INVALID_TRANSITION,
                detail,
                Map.of(
                        "currentStatus",
                        current.name(),
                        "allowedTransitions",
                        Transition.availableFrom(current).stream()
                                .map(Enum::name)
                                .toList()));
    }

    static InvalidTransitionException of(ExceptionStatus current, Transition attempted) {
        return new InvalidTransitionException(
                current, "A case that is " + current + " can't take the transition " + attempted + ".");
    }
}
