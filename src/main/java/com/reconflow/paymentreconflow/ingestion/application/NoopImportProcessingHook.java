package com.reconflow.paymentreconflow.ingestion.application;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** The production {@link ImportProcessingHook}: does nothing. */
@Component
class NoopImportProcessingHook implements ImportProcessingHook {

    @Override
    public void afterRow(UUID batchId, int rowNumber) {}

    @Override
    public void beforeFinishCommit(UUID batchId) {}
}
