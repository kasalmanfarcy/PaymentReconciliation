package com.reconflow.paymentreconflow.ingestion.api;

import com.reconflow.paymentreconflow.ingestion.application.ImportQueries;
import com.reconflow.paymentreconflow.ingestion.application.ImportReceiver;
import com.reconflow.paymentreconflow.ingestion.application.ImportRetryService;
import com.reconflow.paymentreconflow.ingestion.application.ImportView;
import com.reconflow.paymentreconflow.ingestion.application.RowErrorView;
import com.reconflow.paymentreconflow.ingestion.domain.ImportBatchStatus;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import com.reconflow.paymentreconflow.shared.ratelimit.RateLimitPolicy;
import com.reconflow.paymentreconflow.shared.ratelimit.RateLimited;
import com.reconflow.paymentreconflow.shared.web.Pagination;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /api/v1/imports} (api-design.md §8.2–§8.6). The organization comes from TenantContext,
 * never from the request.
 */
@RestController
@RequestMapping("/api/v1/imports")
class ImportController {

    /** Seconds a client should wait before polling a batch that isn't finished (ADR 0003). */
    static final String RETRY_AFTER_SECONDS = "2";

    /** A retry must say why (requirements.md FR-08); it goes into the audit trail. */
    record RetryRequest(@NotBlank @Size(max = 1000) String reason) {}

    private final ImportReceiver receiver;
    private final ImportQueries queries;
    private final ImportRetryService retries;

    ImportController(ImportReceiver receiver, ImportQueries queries, ImportRetryService retries) {
        this.receiver = receiver;
        this.queries = queries;
        this.retries = retries;
    }

    /**
     * 202, not 201: the batch exists but the import, the thing the client cares about, hasn't
     * happened yet. {@code Location} says where to poll. No Idempotency-Key: the content hash is
     * the upload's natural idempotency key.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "The batch was stored and queued; poll the Location"),
        @ApiResponse(responseCode = "400", description = "validation-failed or a bad parameter"),
        @ApiResponse(
                responseCode = "409",
                description = "conflict: the code in the problem detail says which (api-design.md §3.1)"),
        @ApiResponse(responseCode = "413", description = "file-too-large"),
        @ApiResponse(responseCode = "415", description = "unsupported media type")
    })
    @RequiresPermission(Permission.IMPORT_CREATE)
    @RateLimited(RateLimitPolicy.IMPORT_UPLOAD)
    ResponseEntity<ImportView> upload(
            @RequestPart(name = "file", required = false) @Nullable MultipartFile file,
            @RequestParam SourceType sourceType) {
        ImportView batch = receiver.receive(file, sourceType);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/imports/" + batch.id()))
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .body(batch);
    }

    @GetMapping
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A page of imports"),
        @ApiResponse(responseCode = "400", description = "validation-failed or a bad parameter")
    })
    @RequiresPermission(Permission.IMPORT_READ)
    PagedModel<ImportView> list(
            @RequestParam(required = false) @Nullable List<ImportBatchStatus> status,
            @RequestParam(required = false) @Nullable SourceType sourceType,
            @RequestParam(required = false) @Nullable List<String> sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return new PagedModel<>(queries.list(status, sourceType, sort, page, size));
    }

    /** Cheap to poll: one primary-key lookup and one index count. */
    @GetMapping("/{id}")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The import, with Retry-After while it is processing"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)")
    })
    @RequiresPermission(Permission.IMPORT_READ)
    ResponseEntity<ImportView> get(@PathVariable UUID id) {
        return withRetryAfter(queries.get(id));
    }

    @GetMapping("/{id}/errors")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A page of row errors"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)")
    })
    @RequiresPermission(Permission.IMPORT_READ)
    PagedModel<RowErrorView> errors(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) @Nullable List<String> sort) {
        Pagination.requireNoSort(sort, "rowNumber, column");
        return new PagedModel<>(queries.errors(id, page, size));
    }

    /** 202 like the upload: the batch is RECEIVED again and its new job is queued. */
    @PostMapping("/{id}/retry")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "The failed import was queued again"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)"),
        @ApiResponse(
                responseCode = "409",
                description = "conflict: the code in the problem detail says which (api-design.md §3.1)")
    })
    @RequiresPermission(Permission.IMPORT_CREATE)
    @RateLimited(RateLimitPolicy.IMPORT_UPLOAD)
    ResponseEntity<ImportView> retry(@PathVariable UUID id, @Valid @RequestBody RetryRequest request) {
        ImportView batch = retries.retry(id, request.reason());
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/imports/" + batch.id()))
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .body(batch);
    }

    /** {@code Retry-After: 2} while the batch is RECEIVED or PROCESSING: how often to poll. */
    static ResponseEntity<ImportView> withRetryAfter(ImportView batch) {
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (!batch.status().isTerminal()) {
            ok.header("Retry-After", RETRY_AFTER_SECONDS);
        }
        return ok.body(batch);
    }
}
