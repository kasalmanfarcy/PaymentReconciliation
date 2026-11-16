package com.reconflow.paymentreconflow.shared.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 409 with a specific code ({@code duplicate-file}, {@code stale-version}, ...). Thrown where the
 * conflict is detected, e.g. after catching the unique-constraint violation that signals it;
 * a DataIntegrityViolationException is never mapped to 409 globally (it may be a real bug).
 */
public final class ConflictException extends ApiException {

    public ConflictException(String code, String detail, Map<String, Object> properties) {
        super(HttpStatus.CONFLICT, code, detail, properties);
    }

    public ConflictException(String code, String detail) {
        this(code, detail, Map.of());
    }
}
