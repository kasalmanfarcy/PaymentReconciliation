package com.reconflow.paymentreconflow.security.auth;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 400 {@code password-policy}: a new password doesn't meet {@link PasswordPolicy}. */
public class PasswordPolicyException extends ApiException {

    PasswordPolicyException(String detail) {
        super(HttpStatus.BAD_REQUEST, ProblemCodes.PASSWORD_POLICY, detail, Map.of());
    }
}
