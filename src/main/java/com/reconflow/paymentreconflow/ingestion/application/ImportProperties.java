package com.reconflow.paymentreconflow.ingestion.application;

import jakarta.validation.constraints.Min;
import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * {@code reconflow.import.*} (phase-M5 §3.3); validated at startup, so a typo fails the boot rather
 * than the first upload.
 *
 * @param maxRows more data rows than this fails the batch ({@code too-many-rows})
 * @param chunkSize rows per transaction; also the checkpoint granularity
 * @param maxStoredErrors row errors stored per batch; {@code error_rows} still counts every bad row
 * @param oldestTransactionDate rows dated further back are DATE_OUT_OF_RANGE
 * @param newestTransactionDate rows dated further ahead are DATE_OUT_OF_RANGE (and a typo like 2062
 *     can't create a partition decades ahead)
 * @param maxFileSize checked in code too, because the servlet container's multipart limit isn't
 *     applied on every path (and must agree with {@code spring.servlet.multipart.max-file-size})
 */
@Validated
@ConfigurationProperties("reconflow.import")
public record ImportProperties(
        @DefaultValue("500000") @Min(1) int maxRows,
        @DefaultValue("5000") @Min(1) int chunkSize,
        @DefaultValue("10000") @Min(0) int maxStoredErrors,
        @DefaultValue("P2Y") Period oldestTransactionDate,
        @DefaultValue("P31D") Period newestTransactionDate,
        @DefaultValue("50MB") DataSize maxFileSize) {}
