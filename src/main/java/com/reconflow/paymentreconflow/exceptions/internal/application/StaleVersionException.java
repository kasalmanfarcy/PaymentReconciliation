package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * {@code 409 stale-version}, with {@code currentVersion} so the client can re-read and decide.
 * 409 rather than HTTP's strict 412 for a failed If-Match: the project's convention for every
 * "someone else changed this" answer (ADR 0007 records 412 as the alternative).
 */
public final class StaleVersionException extends ApiException {

    StaleVersionException(long currentVersion) {
        super(
                HttpStatus.CONFLICT,
                ProblemCodes.STALE_VERSION,
                "The case was changed by someone else (now version " + currentVersion + "). Reload it and retry.",
                Map.of("currentVersion", currentVersion));
    }
}
