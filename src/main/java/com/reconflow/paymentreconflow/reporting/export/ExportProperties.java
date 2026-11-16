package com.reconflow.paymentreconflow.reporting.export;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code reconflow.export.*} (M8.4).
 *
 * @param syncMaxRows a run with at most this many exceptions streams its CSV in the response; a
 *     larger one goes through an EXPORT job (50,000 rows is a few MB and streams in seconds)
 */
@Validated
@ConfigurationProperties("reconflow.export")
public record ExportProperties(
        @DefaultValue("50000") @Min(0) long syncMaxRows) {}
