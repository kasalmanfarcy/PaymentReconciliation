package com.reconflow.paymentreconflow.shared.error;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * Turns every exception that reaches a controller into the error format of docs/api-design.md
 * §3. Extending {@link ResponseEntityExceptionHandler} gives ProblemDetail rendering for all of
 * Spring MVC's own exceptions (unknown URL, wrong method, unsupported media type, ...);
 * {@link #createResponseEntity} adds {@code type}/{@code code}/{@code requestId} to those, and
 * the overrides below change only what needs a different shape.
 *
 * <p>Errors raised in filters (Spring Security's 401/403) never reach this class; the security
 * configuration writes them with {@link ProblemDetailWriter}.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String INVALID_REQUEST = "Invalid request";

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex) {
        ProblemDetail problem = ProblemDetails.of(ex.status(), ex.code(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return problem;
    }

    /** An optimistic-lock failure that escaped the service layer (M7.8 maps it explicitly too). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleStaleVersion(ObjectOptimisticLockingFailureException ex) {
        return ProblemDetails.of(
                HttpStatus.CONFLICT,
                ProblemCodes.STALE_VERSION,
                "The resource was changed by someone else. Reload it and retry.");
    }

    /**
     * Security exceptions thrown inside a controller (e.g. by {@code @PreAuthorize}) must reach
     * Spring Security's ExceptionTranslationFilter, which decides between 401 and 403 and writes
     * the response. Without this, the catch-all below would turn them into 500s.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void rethrowSecurityExceptions(RuntimeException ex) {
        throw ex;
    }

    /**
     * Last resort: log everything, reveal nothing. {@code ex.getMessage()} may contain SQL,
     * table names or data, so it goes to the log (with the requestId from the MDC), never to
     * the client.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetails.of(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemCodes.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the requestId when reporting it.");
    }

    /** Bean Validation on a {@code @Valid @RequestBody}: one {@code errors[]} entry per field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldError)
                .toList();
        return validationFailed("The request body is invalid.", errors, headers);
    }

    /** Bean Validation on {@code @RequestParam}/{@code @PathVariable}/header constraints. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> error(
                                result.getMethodParameter().getParameterName(),
                                lastCode(error.getCodes()),
                                error.getDefaultMessage())))
                .toList();
        return validationFailed("The request is invalid.", errors, headers);
    }

    /**
     * Malformed JSON or a value of the wrong type. The parser's message is not returned (it can
     * name internal classes); only the JSON path of the bad value, when known.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = List.of();
        if (ex.getCause() instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
            errors = List.of(error(jsonPath(jackson), "unreadable", "The value could not be read."));
        }
        return validationFailed("The request body is not valid JSON, or a value has the wrong type.", errors, headers);
    }

    /** {@code ?page=abc}: a parameter that can't be converted to its declared type. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex.getPropertyName() != null ? ex.getPropertyName() : "request";
        return validationFailed(
                "The request is invalid.",
                List.of(error(field, "type-mismatch", "The value has the wrong type.")),
                headers);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        return validationFailed(
                "The request is invalid.",
                List.of(error(ex.getParameterName(), "required", "The parameter is required.")),
                headers);
    }

    /**
     * Every response this class builds through the base class ends here, including the
     * ProblemDetails Spring MVC creates for its own exceptions: add type, code and requestId.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            ProblemDetails.withDefaultCode(problem);
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private ResponseEntity<Object> validationFailed(
            String detail, List<Map<String, String>> errors, HttpHeaders headers) {
        ProblemDetail problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED, detail);
        problem.setTitle(INVALID_REQUEST);
        problem.setProperty(ProblemDetails.ERRORS, errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    private static Map<String, String> fieldError(FieldError error) {
        return error(error.getField(), String.valueOf(error.getCode()), error.getDefaultMessage());
    }

    private static Map<String, String> error(String field, String code, @Nullable String message) {
        return Map.of("field", field, "code", kebab(code), "message", String.valueOf(message));
    }

    /** Constraint names to the API's kebab-case: NotBlank -> not-blank, DecimalMin -> decimal-min. */
    static String kebab(String code) {
        return code.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    /** MessageSourceResolvable codes run from most to least specific; the last is the bare constraint name. */
    private static String lastCode(String @Nullable [] codes) {
        return codes == null || codes.length == 0 ? "invalid" : codes[codes.length - 1];
    }

    /** {@code [items, 0, amount]} -> {@code items[0].amount}. */
    private static String jsonPath(JacksonException ex) {
        return ex.getPath().stream()
                .flatMap(ref -> ref.getPropertyName() != null
                        ? Stream.of("." + ref.getPropertyName())
                        : Stream.of("[" + ref.getIndex() + "]"))
                .collect(Collectors.joining())
                .replaceFirst("^\\.", "");
    }
}
