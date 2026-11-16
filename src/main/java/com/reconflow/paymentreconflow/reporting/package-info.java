/**
 * Reporting: read-only aggregate reports and CSV export, written as SQL over the other
 * modules' tables. Owns no tables. Depends on {@code shared}, and on {@code audit} since M8.4:
 * an export is financial data leaving the system, so it is audited.
 *
 * <p>Public API: this package. Sub-packages are internal to the module.
 */
// Allowed dependencies: docs/architecture.md §4. A dependency not listed here fails ModularityTest,
// even if it would not create a cycle.
@ApplicationModule(
        displayName = "Reporting",
        allowedDependencies = {"audit", "shared"})
package com.reconflow.paymentreconflow.reporting;

import org.springframework.modulith.ApplicationModule;
