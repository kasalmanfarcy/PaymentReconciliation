package com.reconflow.paymentreconflow.audit;

/**
 * The kind of thing an audit event is about. Same rule as {@link AuditAction}: <strong>never
 * rename or remove a constant</strong>, because stored rows hold the name.
 */
public enum AuditEntityType {
    USER,
    MEMBERSHIP,
    ORGANIZATION,
    REFRESH_TOKEN_FAMILY,
    IMPORT_BATCH,
    RECONCILIATION_RUN,
    EXCEPTION_CASE
}
