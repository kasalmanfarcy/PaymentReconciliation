package com.reconflow.paymentreconflow.shared.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Base for errors the API reports deliberately. Services throw a subclass; the global handler
 * turns it into a ProblemDetail with this status, code and detail, plus {@link #properties()}
 * as extension members (e.g. {@code existingBatchId}).
 *
 * <p>The detail message is shown to the client, so it must never contain internal information
 * (SQL, class names, other tenants' data).
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties;

    protected ApiException(HttpStatus status, String code, String detail, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
