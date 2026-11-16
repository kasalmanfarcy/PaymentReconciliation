package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * {@code 422 invalid-assignee}: the user can't take the case. Deliberately one answer for "not a
 * member", "a member of another organization", "disabled", "lacks EXCEPTION_UPDATE" and "no such
 * user", so the response never confirms that a user exists elsewhere (api-design.md §4).
 */
public final class InvalidAssigneeException extends ApiException {

    InvalidAssigneeException() {
        super(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ProblemCodes.INVALID_ASSIGNEE,
                "The assignee must be an active member of this organization who may work exceptions.",
                Map.of());
    }
}
