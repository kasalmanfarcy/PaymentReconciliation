/**
 * Ledger: the normalized transaction store, its writer and its search API.
 * Owns {@code transaction_record} and its monthly partitions.
 *
 * <p>Public API: this package. Sub-packages are internal to the module.
 */
// Allowed dependencies: docs/architecture.md §4. A dependency not listed here fails ModularityTest,
// even if it would not create a cycle.
@ApplicationModule(displayName = "Ledger", allowedDependencies = {"shared"})
package com.reconflow.paymentreconflow.ledger;

import org.springframework.modulith.ApplicationModule;
