package com.reconflow.paymentreconflow.reconciliation.application;

import java.util.Map;
import java.util.UUID;

/** The {@code RECONCILE} job's type and payload, shared by the enqueuers and the handler. */
public final class ReconcileJobs {

    public static final String TYPE = "RECONCILE";
    public static final String RUN_ID = "runId";

    private ReconcileJobs() {}

    public static Map<String, String> payload(UUID runId) {
        return Map.of(RUN_ID, runId.toString());
    }
}
