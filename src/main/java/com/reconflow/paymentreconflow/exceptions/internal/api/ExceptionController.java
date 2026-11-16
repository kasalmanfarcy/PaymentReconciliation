package com.reconflow.paymentreconflow.exceptions.internal.api;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionFilter;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionList;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionQueries;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionSummaryView;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/exceptions} (api-design.md §8.13–§8.18). The organization comes from
 * TenantContext, never from the request. Under {@code internal}: Modulith restricts Java type
 * dependencies between modules, and nothing outside should call a controller.
 */
@RestController
@RequestMapping("/api/v1/exceptions")
class ExceptionController {

    private final ExceptionQueries queries;
    private final ExceptionList list;

    ExceptionController(ExceptionQueries queries, ExceptionList list) {
        this.queries = queries;
        this.list = list;
    }

    /**
     * The work queue, newest first, default {@code status=OPEN&status=IN_REVIEW}. Filters are named
     * after the response fields (api-design.md §4): {@code assignedTo} takes a user id, {@code me}
     * or {@code unassigned}; {@code createdFrom}/{@code createdTo} are inclusive UTC dates;
     * {@code amountMin}/{@code amountMax} are decimal strings.
     */
    @GetMapping
    @RequiresPermission(Permission.EXCEPTION_READ)
    PagedModel<ExceptionSummaryView> list(
            @RequestParam(required = false) @Nullable List<ExceptionType> type,
            @RequestParam(required = false) @Nullable List<ExceptionStatus> status,
            @RequestParam(required = false) @Nullable String assignedTo,
            @RequestParam(required = false) @Nullable UUID runId,
            @RequestParam(required = false) @Nullable LocalDate createdFrom,
            @RequestParam(required = false) @Nullable LocalDate createdTo,
            @RequestParam(required = false) @Nullable BigDecimal amountMin,
            @RequestParam(required = false) @Nullable BigDecimal amountMax,
            @RequestParam(required = false) @Nullable List<String> sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var filter = new ExceptionFilter(type, status, assignedTo, runId, createdFrom, createdTo, amountMin, amountMax);
        return new PagedModel<>(list.list(filter, sort, page, size));
    }

    /**
     * The case, both sides' records, runs, notes and history. {@code ETag: "<version>"}: a strong
     * validator built from {@code exception_case.version}, which every write bumps; the client sends
     * it back in {@code If-Match} (ADR 0007).
     */
    @GetMapping("/{id}")
    @RequiresPermission(Permission.EXCEPTION_READ)
    ResponseEntity<ExceptionDetailView> get(@PathVariable UUID id) {
        return withETag(queries.detail(id));
    }

    static ResponseEntity<ExceptionDetailView> withETag(ExceptionDetailView view) {
        return ResponseEntity.ok().eTag(Long.toString(view.version())).body(view);
    }
}
