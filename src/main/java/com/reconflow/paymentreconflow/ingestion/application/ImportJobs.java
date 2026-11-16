package com.reconflow.paymentreconflow.ingestion.application;

import java.util.Map;
import java.util.UUID;

/** The {@code IMPORT} job's type and payload, shared by the enqueuers and the handler. */
public final class ImportJobs {

    public static final String TYPE = "IMPORT";
    public static final String BATCH_ID = "batchId";

    private ImportJobs() {}

    public static Map<String, String> payload(UUID batchId) {
        return Map.of(BATCH_ID, batchId.toString());
    }
}
