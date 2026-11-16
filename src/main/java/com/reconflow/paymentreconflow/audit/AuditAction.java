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
    /**
     * Deprecated, never written: reconciliation-rules.md §7.1 names the event
     * {@link #IMPORT_RETRY_REQUESTED}. Kept because constants are never removed.
     */
    IMPORT_RETRIED,
    IMPORT_RETRY_REQUESTED,
    // M6: reconciliation
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_REQUESTED,
    RUN_RETRY_REQUESTED,
    // M7: exceptions
    EXCEPTION_CREATED,
    EXCEPTION_ASSIGNED,
    EXCEPTION_STATUS_CHANGED,
    EXCEPTION_NOTE_ADDED,
    EXCEPTION_RESOLVED,
    EXCEPTION_REOPENED,
    // M8: reports
    /** A large export was queued as a background job (payload: runId, jobId, rowCount). */
    EXPORT_REQUESTED,
    /** A run's exceptions left the system as CSV (payload: runId, rowCount, mode, jobId if async). */
    EXCEPTIONS_EXPORTED,
    // M9: data lifecycle
    /** An old month of the organization's records left PostgreSQL for verified files in object storage. */
    PARTITION_ARCHIVED,
    // M10.1: identity provider. LOGIN_*, ACCOUNT_LOCKED, LOGOUT, PASSWORD_CHANGED and
    // REFRESH_TOKEN_REUSE are no longer written (Keycloak's login and admin events record them) but
    // stay, because stored rows hold their names.
    /** A Keycloak account was linked to an existing user by verified email, on its first request. */
    USER_LINKED
}
