/**
 * Audit: {@code AuditService.record(...)}, which joins the caller's transaction, and the audit
 * timeline API. Owns the append-only {@code audit_event} table.
 *
 * <p>Public API: this package. Sub-packages are internal to the module.
 */
package com.reconflow.paymentreconflow.audit;
