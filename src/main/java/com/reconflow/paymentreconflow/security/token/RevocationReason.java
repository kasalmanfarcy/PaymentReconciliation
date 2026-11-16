package com.reconflow.paymentreconflow.security.token;

/**
 * Why a refresh token stopped being valid; matches the CHECK added in V2. Only {@link #ROTATED}
 * means "this exact value was already exchanged", so only presenting a ROTATED token again is
 * reuse (authentication.plan §4.3 rule 2). The others are expected: after a logout or a password
 * change, the user's other devices still hold old cookies and will try them.
 */
public enum RevocationReason {
    ROTATED,
    LOGOUT,
    REUSE_DETECTED,
    PASSWORD_CHANGED,
    ADMIN_REVOKED,
    USER_DISABLED
}
