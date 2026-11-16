/**
 * Ingestion: file upload and storage, streaming CSV parsing, validation, normalization and
 * import status. Owns {@code import_batch} and {@code import_row_error}.
 *
 * <p>Public API: this package. Sub-packages are internal to the module.
 */
// Allowed dependencies: docs/architecture.md §4. A dependency not listed here fails ModularityTest,
// even if it would not create a cycle.
@ApplicationModule(
        displayName = "Ingestion",
        allowedDependencies = {"ledger", "audit", "shared"})
package com.reconflow.paymentreconflow.ingestion;

import org.springframework.modulith.ApplicationModule;
