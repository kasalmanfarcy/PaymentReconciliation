package com.reconflow.paymentreconflow.security.idp;

import com.reconflow.paymentreconflow.shared.error.ApiException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The identity provider didn't do what was asked (down, timed out, refused). {@code 503}: the
 * request may well succeed later, and nothing was changed in ReconFlow (the caller's transaction
 * rolls back). The IdP's own response is logged by status only, never its body.
 */
public class IdentityProviderException extends ApiException {

    public IdentityProviderException(String operation, Throwable cause) {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemCodes.IDENTITY_PROVIDER_UNAVAILABLE,
                "The identity provider is unavailable (" + operation + "). Nothing was changed; retry later.",
                Map.of());
        initCause(cause);
    }
}
