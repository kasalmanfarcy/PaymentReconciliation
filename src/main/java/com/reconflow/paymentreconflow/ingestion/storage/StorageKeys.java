package com.reconflow.paymentreconflow.ingestion.storage;

import java.util.UUID;

/** The one place storage keys are built: server-generated IDs only, never user input. */
public final class StorageKeys {

    private StorageKeys() {}

    public static String original(UUID organizationId, UUID batchId) {
        return importsPrefix(organizationId) + batchId + "/original.csv";
    }

    public static String importsPrefix(UUID organizationId) {
        return organizationId + "/imports/";
    }
}
