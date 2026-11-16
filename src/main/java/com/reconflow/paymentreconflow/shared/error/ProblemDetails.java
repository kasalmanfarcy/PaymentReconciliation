package com.reconflow.paymentreconflow.shared.error;

import com.reconflow.paymentreconflow.shared.web.RequestIdFilter;
import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the one error shape of docs/api-design.md §3: an RFC 9457 ProblemDetail whose
 * {@code type} is {@code https://reconflow.dev/problems/<code>}, plus the {@code code} and
 * {@code requestId} extension members. Used by GlobalExceptionHandler and, for errors raised
 * in filters before any controller runs (401/403), by ProblemDetailWriter.
 */
public final class ProblemDetails {

    public static final String TYPE_BASE = "https://reconflow.dev/problems/";
    static final String CODE = "code";
    static final String REQUEST_ID = "requestId";
    static final String ERRORS = "errors";

    private ProblemDetails() {}

    /** A new problem with the given status, code and human-readable detail. */
    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        return withCode(ProblemDetail.forStatusAndDetail(status, detail), code);
    }

    /**
     * Adds {@code type}, {@code code} and {@code requestId} to a problem, keeping a code that is
     * already set (a specific handler's choice beats the status-based default).
     */
    public static ProblemDetail withCode(ProblemDetail problem, String code) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey(CODE)) {
            problem.setType(URI.create(TYPE_BASE + code));
            problem.setProperty(CODE, code);
        }
        // Validation errors always carry errors[], even when the framework gave no field detail.
        if (ProblemCodes.VALIDATION_FAILED.equals(problem.getProperties().get(CODE))
                && !problem.getProperties().containsKey(ERRORS)) {
            problem.setProperty(ERRORS, List.of());
        }
        problem.setProperty(REQUEST_ID, MDC.get(RequestIdFilter.MDC_KEY));
        return problem;
    }

    /** Adds the default code for a problem Spring MVC built itself. */
    public static ProblemDetail withDefaultCode(ProblemDetail problem) {
        return withCode(problem, ProblemCodes.forStatus(problem.getStatus()));
    }
}
