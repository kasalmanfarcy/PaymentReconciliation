package com.reconflow.paymentreconflow.shared.error;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 400 {@code validation-failed} raised by code rather than Bean Validation (an unknown sort field,
 * a bad combination of parameters), with the same {@code errors[]} shape.
 */
public final class ValidationFailedException extends ApiException {

    public ValidationFailedException(String field, String code, String message) {
        super(
                HttpStatus.BAD_REQUEST,
                ProblemCodes.VALIDATION_FAILED,
                "The request is invalid.",
                Map.of(ProblemDetails.ERRORS, List.of(Map.of("field", field, "code", code, "message", message))));
    }
}
