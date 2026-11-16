/**
 * Organization: the tenant boundary. Platform admins create, suspend and list organizations.
 * Owns the {@code organization} table.
 *
 * <p>Public API: this package. Sub-packages are internal to the module.
 */
// Allowed dependencies: docs/architecture.md §4. A dependency not listed here fails ModularityTest,
// even if it would not create a cycle.
@ApplicationModule(
        displayName = "Organization",
        allowedDependencies = {"audit", "shared"})
package com.reconflow.paymentreconflow.organization;

import org.springframework.modulith.ApplicationModule;
