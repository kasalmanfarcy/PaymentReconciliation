package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The list filters of api-design.md §4 and §8.13, as bound from the query string. Names follow the
 * response fields ({@code assignedTo}, not "assignee"); ranges use {@code From}/{@code To} for dates
 * (inclusive) and {@code Min}/{@code Max} for amounts.
 *
 * @param assignedTo a user id, {@code me} or {@code unassigned}
 */
public record ExceptionFilter(
        @Nullable List<ExceptionType> type,
        @Nullable List<ExceptionStatus> status,
        @Nullable String assignedTo,
        @Nullable UUID runId,
        @Nullable LocalDate createdFrom,
        @Nullable LocalDate createdTo,
        @Nullable BigDecimal amountMin,
        @Nullable BigDecimal amountMax) {}
