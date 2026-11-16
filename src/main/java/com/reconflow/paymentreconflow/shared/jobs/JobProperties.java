package com.reconflow.paymentreconflow.shared.jobs;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code reconflow.jobs.*}: queue timing (ADR 0004, scalability.md §4). Whether this node runs
 * the worker at all is {@link JobWorkerProperties}.
 *
 * @param lease how long a claim is valid without a heartbeat or a fence
 * @param heartbeat how often a live worker extends its leases; well under {@code lease}
 * @param perOrgMaxRunning the fairness cap: one tenant's 30 files can't occupy every worker
 * @param baseBackoff the first retry delay; doubles per attempt, up to {@code maxBackoff}
 */
@Validated
@ConfigurationProperties("reconflow.jobs")
public record JobProperties(
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("2m") Duration lease,
        @DefaultValue("30s") Duration heartbeat,
        @DefaultValue("30s") Duration sweepInterval,
        @DefaultValue("2") @Min(1) int perOrgMaxRunning,
        @DefaultValue("10s") Duration baseBackoff,
        @DefaultValue("10m") Duration maxBackoff) {}
