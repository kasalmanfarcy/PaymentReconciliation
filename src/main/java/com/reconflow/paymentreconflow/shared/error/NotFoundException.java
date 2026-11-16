package com.reconflow.paymentreconflow.shared.error;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * 404 {@code not-found}. Also used for another tenant's IDs: "doesn't exist" and "isn't yours"
 * must be indistinguishable, or IDs could be probed across tenants (ADR 0008).
 */
public final class NotFoundException extends ApiException {

    public NotFoundException(String entity, UUID id) {
        super(HttpStatus.NOT_FOUND, ProblemCodes.NOT_FOUND, entity + " " + id + " was not found.", Map.of());
    }
}
