/**
 * Reconciliation: runs, the set-based matching engine and matches.
 * Owns {@code reconciliation_run} and {@code reconciliation_match}.
 *
 * <p>Public API: this package. Sub-packages are internal to the module.
 */
// Allowed dependencies: docs/architecture.md §4. A dependency not listed here fails ModularityTest,
// even if it would not create a cycle.
@ApplicationModule(displayName = "Reconciliation", allowedDependencies = {"ledger", "ingestion", "exceptions", "audit", "shared"})
package com.reconflow.paymentreconflow.reconciliation;

import org.springframework.modulith.ApplicationModule;
