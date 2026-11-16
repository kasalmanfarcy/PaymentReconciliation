/**
 * Audit: {@code AuditService.record(...)}, which joins the caller's transaction, and the audit
 * timeline API. Owns the append-only {@code audit_event} table.
 *
 * <p>Public API: this package. Sub-packages are internal to the module.
 */
// Allowed dependencies: docs/architecture.md §4. A dependency not listed here fails ModularityTest,
// even if it would not create a cycle.
@ApplicationModule(displayName = "Audit", allowedDependencies = {"shared"})
package com.reconflow.paymentreconflow.audit;

import org.springframework.modulith.ApplicationModule;
