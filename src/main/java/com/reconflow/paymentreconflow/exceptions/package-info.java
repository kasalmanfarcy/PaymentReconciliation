/**
 * Exceptions: discrepancy cases, the workflow state machine, notes and status history.
 * Owns {@code exception_case}, {@code exception_case_record}, {@code exception_note} and
 * {@code exception_status_history} ({@code exception_case_run} from M6).
 *
 * <p>Public API: this package (e.g. DiscrepancyRecorder, M6). Sub-packages are internal.
 */
// Allowed dependencies: docs/architecture.md §4. A dependency not listed here fails ModularityTest,
// even if it would not create a cycle.
@ApplicationModule(
        displayName = "Exceptions",
        allowedDependencies = {"audit", "shared", "security :: directory"})
package com.reconflow.paymentreconflow.exceptions;

import org.springframework.modulith.ApplicationModule;
