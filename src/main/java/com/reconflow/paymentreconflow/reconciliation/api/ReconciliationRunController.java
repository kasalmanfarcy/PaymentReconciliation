package com.reconflow.paymentreconflow.reconciliation.api;

import com.reconflow.paymentreconflow.reconciliation.application.ReconciliationRunService;
import com.reconflow.paymentreconflow.reconciliation.application.RunRequest;
import com.reconflow.paymentreconflow.reconciliation.application.RunView;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import jakarta.validation.Valid;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/reconciliation-runs} (api-design.md §8.9–§8.13). The organization comes from
 * TenantContext, never from the request.
 */
@RestController
@RequestMapping("/api/v1/reconciliation-runs")
class ReconciliationRunController {

    /** Seconds a client should wait before polling a run that isn't finished (ADR 0003). */
    static final String RETRY_AFTER_SECONDS = "2";

    private final ReconciliationRunService runs;

    ReconciliationRunController(ReconciliationRunService runs) {
        this.runs = runs;
    }

    /**
     * 202 for a new run: it is PENDING and the work, the thing the client cares about, happens
     * later (201 would claim it is complete). 200 with the existing run, in whatever state, for a
     * repeated request (ADR 0006).
     */
    @PostMapping
    @RequiresPermission(Permission.RUN_CREATE)
    ResponseEntity<RunView> create(
            @RequestHeader(name = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @Valid @RequestBody RunRequest request) {
        ReconciliationRunService.Created result = runs.create(request, idempotencyKey);
        RunView run = result.run();
        if (result.isNew()) {
            return ResponseEntity.accepted()
                    .location(location(run))
                    .header("Retry-After", RETRY_AFTER_SECONDS)
                    .body(run);
        }
        return withRetryAfter(ResponseEntity.ok().location(location(run)), run);
    }

    static URI location(RunView run) {
        return URI.create("/api/v1/reconciliation-runs/" + run.id());
    }

    /** {@code Retry-After: 2} while the run is PENDING or RUNNING: how often to poll. */
    static ResponseEntity<RunView> withRetryAfter(ResponseEntity.BodyBuilder ok, RunView run) {
        if (!run.status().isTerminal()) {
            ok.header("Retry-After", RETRY_AFTER_SECONDS);
        }
        return ok.body(run);
    }
}
