package com.reconflow.paymentreconflow.reconciliation.engine;

import com.reconflow.paymentreconflow.ledger.SourceType;
import java.time.LocalDate;
import java.util.UUID;

/** What the engine needs to know about a run: its tenant, period (inclusive), sources and currency. */
public record RunContext(
        UUID organizationId,
        UUID runId,
        LocalDate periodStart,
        LocalDate periodEnd,
        SourceType leftSource,
        SourceType rightSource,
        String currency) {}
