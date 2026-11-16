package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * {@code 409 active-case-exists}: reopening would make a second active case for one discrepancy,
 * which {@code exception_case_active_uk} forbids (M7.3). Happens when a later run saw the
 * discrepancy again while this case was resolved and opened a new case for it (rule K4). The body
 * points at that case ({@code existingCaseId}); the admin decides, nothing is merged automatically.
 */
public final class ActiveCaseExistsException extends ApiException {

    ActiveCaseExistsException(@Nullable UUID existingCaseId) {
        super(
                HttpStatus.CONFLICT,
                ProblemCodes.ACTIVE_CASE_EXISTS,
                "Another case for the same discrepancy is already active; work that one instead.",
                properties(existingCaseId));
    }

    private static Map<String, Object> properties(@Nullable UUID existingCaseId) {
        Map<String, Object> p = new HashMap<>();
        p.put("existingCaseId", existingCaseId == null ? null : existingCaseId.toString());
        return p;
    }
}
