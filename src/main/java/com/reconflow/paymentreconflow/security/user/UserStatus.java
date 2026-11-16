package com.reconflow.paymentreconflow.security.user;

/**
 * Matches the CHECK on {@code app_user.status}. {@code LOCKED} is administrative (an admin decided
 * to block the account) and never expires; brute-force lockout uses {@code locked_until} instead
 * and never sets this status (authentication.plan §4.5).
 */
public enum UserStatus {
    ACTIVE,
    LOCKED,
    DISABLED
}
