package com.reconflow.paymentreconflow.ingestion.api;

import com.reconflow.paymentreconflow.ingestion.application.ImportReceiver;
import com.reconflow.paymentreconflow.ingestion.application.ImportView;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final ImportReceiver receiver;

    ImportController(ImportReceiver receiver) {
        this.receiver = receiver;
    }

    /**
     * 202, not 201: the batch exists but the import, the thing the client cares about, hasn't
     * happened yet. {@code Location} says where to poll. No Idempotency-Key: the content hash is
     * the upload's natural idempotency key.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(Permission.IMPORT_CREATE)
    ResponseEntity<ImportView> upload(
            @RequestPart(name = "file", required = false) @Nullable MultipartFile file,
            @RequestParam SourceType sourceType) {
        ImportView batch = receiver.receive(file, sourceType);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/imports/" + batch.id()))
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .body(batch);
    }
}
