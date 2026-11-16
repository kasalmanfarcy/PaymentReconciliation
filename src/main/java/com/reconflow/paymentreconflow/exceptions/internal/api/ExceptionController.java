package com.reconflow.paymentreconflow.exceptions.internal.api;

import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionQueries;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    ExceptionController(ExceptionQueries queries) {
        this.queries = queries;
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
