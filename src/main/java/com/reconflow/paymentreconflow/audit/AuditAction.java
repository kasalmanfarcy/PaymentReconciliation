package com.reconflow.paymentreconflow.audit;

/**
 * What happened. Typed so modules can't drift between "EXCEPTION_RESOLVED" and
 * "EXCEPTON_RESOLVED".
 *
 * <p><strong>Never rename or remove a constant</strong>: stored rows hold the name, and an
 * unknown name can no longer be read back. Add new constants; mark retired ones deprecated in a
 * comment. Later phases add theirs as they need them.
 */
public enum AuditAction {
    // M3: authentication, membership and organizations
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    ACCOUNT_LOCKED,
    LOGOUT,
    PASSWORD_CHANGED,
    REFRESH_TOKEN_REUSE,
    PASSWORD_RESET_BY_ADMIN,
    MEMBER_CREATED,
    MEMBER_ROLE_CHANGED,
    MEMBER_DISABLED,
    ORGANIZATION_CREATED,
    PLATFORM_ADMIN_BOOTSTRAPPED,
    // M5: imports
    IMPORT_RECEIVED,
    IMPORT_COMPLETED,
    IMPORT_FAILED,
    IMPORT_RETRIED,
    // M6: reconciliation
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_FAILED,
    // M7: exceptions
    EXCEPTION_CREATED,
    EXCEPTION_ASSIGNED,
    EXCEPTION_STATUS_CHANGED,
    EXCEPTION_NOTE_ADDED,
    EXCEPTION_RESOLVED,
    EXCEPTION_REOPENED
}
