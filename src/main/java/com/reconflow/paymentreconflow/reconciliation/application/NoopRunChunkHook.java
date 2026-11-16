package com.reconflow.paymentreconflow.reconciliation.application;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The production {@link RunChunkHook}: does nothing. */
@Component
class NoopRunChunkHook implements RunChunkHook {

    @Override
    public void beforeCheckpoint(UUID runId, LocalDate day) {}
}
