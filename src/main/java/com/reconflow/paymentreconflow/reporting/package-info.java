/**
 * Reporting: read-only aggregate reports and CSV export, written as SQL over the other
 * modules' tables. Owns no tables and depends only on {@code shared}.
 *
 * <p>Public API: this package. Sub-packages are internal to the module.
 */
// Allowed dependencies: docs/architecture.md §4. A dependency not listed here fails ModularityTest,
// even if it would not create a cycle.
@ApplicationModule(
        displayName = "Reporting",
        allowedDependencies = {"shared"})
package com.reconflow.paymentreconflow.reporting;

import org.springframework.modulith.ApplicationModule;
