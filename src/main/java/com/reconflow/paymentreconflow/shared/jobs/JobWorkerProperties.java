package com.reconflow.paymentreconflow.shared.jobs;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code reconflow.jobs.worker.*}: whether this process runs the job worker, and with how many
 * threads. The {@code worker} profile sets {@code enabled=true}; api-only nodes leave it off.
 */
@Validated
@ConfigurationProperties("reconflow.jobs.worker")
public record JobWorkerProperties(
        boolean enabled, @DefaultValue("4") @Min(1) int threads) {}
