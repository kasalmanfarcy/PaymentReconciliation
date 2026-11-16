package com.reconflow.paymentreconflow.reporting.export;

import java.util.UUID;

/**
 * Where an export's file lives: {@code {orgId}/exports/{jobId}.csv}, server-generated IDs only.
 * Named after the job, so a retried job overwrites its own object instead of leaving a second one:
 * idempotent by construction. Under the organization's prefix like the uploads, but outside
 * {@code imports/}, so ingestion's orphan sweeper never looks at it.
 */
public final class ExportKeys {

    private ExportKeys() {}

    public static String file(UUID organizationId, UUID jobId) {
        return organizationId + "/exports/" + jobId + ".csv";
    }
}
