package com.reconflow.paymentreconflow.reporting.export;

import java.util.UUID;

/**
 * The sync/async threshold, as a seam. Production reads the property for every organization; the
 * tests' {@code @Primary} implementation lowers it for one test organization only, so the 202 path
 * can be tested with three exceptions without a second Spring context (the pattern of M5/M6's
 * armable hooks).
 */
public interface ExportLimit {

    long syncMaxRows(UUID organizationId);
}
