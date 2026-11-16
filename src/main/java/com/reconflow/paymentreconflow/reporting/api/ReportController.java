package com.reconflow.paymentreconflow.reporting.api;

import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingReport;
import com.reconflow.paymentreconflow.reporting.ageing.ExceptionAgeingReports;
import com.reconflow.paymentreconflow.reporting.export.ExceptionCsvExporter;
import com.reconflow.paymentreconflow.reporting.export.ExceptionExports;
import com.reconflow.paymentreconflow.reporting.export.ExportView;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReport;
import com.reconflow.paymentreconflow.reporting.importquality.ImportQualityReports;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReport;
import com.reconflow.paymentreconflow.reporting.reconciliation.ReconciliationReports;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresBothPermissions;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import com.reconflow.paymentreconflow.shared.storage.FileStorage;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /api/v1/reports} (api-design.md §8.21–§8.25): read-only aggregate reports and the
 * exception export. The organization always comes from TenantContext, never from a parameter.
 */
@RestController
@RequestMapping("/api/v1/reports")
class ReportController {

    private final ReconciliationReports reconciliation;
    private final ImportQualityReports importQuality;
    private final ExceptionAgeingReports ageing;
    private final ExceptionExports exports;
    private final ExceptionCsvExporter exporter;
    private final FileStorage storage;
    private final JsonMapper json;

    /** CSV per RFC 4180, with the charset spelled out for clients that would otherwise guess. */
    static final MediaType TEXT_CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    /** Seconds a client should wait before polling an export that isn't finished (ADR 0003). */
    static final String RETRY_AFTER_SECONDS = "2";

    ReportController(
            ReconciliationReports reconciliation,
            ImportQualityReports importQuality,
            ExceptionAgeingReports ageing,
            ExceptionExports exports,
            ExceptionCsvExporter exporter,
            FileStorage storage,
            JsonMapper json) {
        this.reconciliation = reconciliation;
        this.importQuality = importQuality;
        this.ageing = ageing;
        this.exports = exports;
        this.exporter = exporter;
        this.storage = storage;
        this.json = json;
    }

    /**
     * {@code ?runId=} or {@code ?from=&to=&currency=}, exactly one of the two. Needs
     * {@code EXCEPTION_READ} too: it shows the cases and who resolved them.
     */
    @GetMapping("/reconciliation")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The reconciliation report"),
        @ApiResponse(responseCode = "400", description = "validation-failed or a bad parameter"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)"),
        @ApiResponse(
                responseCode = "409",
                description = "conflict: the code in the problem detail says which (api-design.md §3.1)")
    })
    @RequiresBothPermissions(value = Permission.REPORT_READ, and = Permission.EXCEPTION_READ)
    ReconciliationReport reconciliation(
            @RequestParam(required = false) @Nullable UUID runId,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(required = false) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable String currency) {
        return reconciliation.report(runId, from, to, currency);
    }

    /**
     * {@code ?batchId=}, or {@code ?from=&to=} (UTC days) with optional {@code granularity}
     * ({@code day} by default, {@code week}, {@code month}) and {@code sourceType}.
     */
    @GetMapping("/import-quality")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Import quality"),
        @ApiResponse(responseCode = "400", description = "validation-failed or a bad parameter"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)")
    })
    @RequiresPermission(Permission.REPORT_READ)
    ImportQualityReport importQuality(
            @RequestParam(required = false) @Nullable UUID batchId,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(required = false) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable String granularity,
            @RequestParam(required = false) @Nullable String sourceType) {
        return importQuality.report(batchId, from, to, granularity, sourceType);
    }

    /**
     * Open and in-review cases by age bucket and assignee. Filters: {@code type} (repeatable),
     * {@code assignedTo} (a user id, {@code me} or {@code unassigned}), {@code currency}.
     */
    @GetMapping("/exception-ageing")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Exception ageing"),
        @ApiResponse(responseCode = "400", description = "validation-failed or a bad parameter")
    })
    @RequiresPermission(Permission.REPORT_READ)
    ExceptionAgeingReport exceptionAgeing(
            @RequestParam(required = false) @Nullable List<String> type,
            @RequestParam(required = false) @Nullable String assignedTo,
            @RequestParam(required = false) @Nullable String currency) {
        return ageing.report(type, assignedTo, currency);
    }

    /**
     * A run's exceptions as CSV. Up to {@code reconflow.export.sync-max-rows}: {@code 200}, streamed
     * while it is read from the database. Above: {@code 202} and a background job, polled at
     * {@code Location}.
     *
     * <p>{@link StreamingResponseBody} runs on an MVC async thread <em>after</em> this method has
     * returned, outside any transaction and without the request's tenant binding. So everything
     * it needs (the tenant, the run) is captured here, and the exporter opens its own transaction
     * with the tenant bound again. Reading TenantContext inside the lambda is the classic bug.
     *
     * <p>Both answers are a StreamingResponseBody, because MVC picks the streaming handler from the
     * declared return type; the 202's small JSON body is simply written by it.
     */
    @GetMapping("/exceptions/export")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The CSV, streamed (runs up to the sync limit)"),
        @ApiResponse(responseCode = "202", description = "An export job was queued (larger runs); poll the Location"),
        @ApiResponse(responseCode = "400", description = "validation-failed or a bad parameter"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)")
    })
    @RequiresBothPermissions(value = Permission.REPORT_READ, and = Permission.EXCEPTION_READ)
    ResponseEntity<StreamingResponseBody> exportExceptions(@RequestParam UUID runId) {
        return switch (exports.request(runId)) {
            case ExceptionExports.Plan.Stream stream -> {
                UUID orgId = stream.organizationId();
                UUID run = stream.runId();
                StreamingResponseBody body = out -> exporter.write(orgId, run, out);
                yield ResponseEntity.ok()
                        .contentType(TEXT_CSV)
                        .header(HttpHeaders.CONTENT_DISPOSITION, attachment("exceptions-" + run + ".csv"))
                        .body(body);
            }
            case ExceptionExports.Plan.Queued queued -> {
                ExportView export = queued.export();
                StreamingResponseBody body = out -> json.writeValue(out, export);
                yield ResponseEntity.accepted()
                        .location(URI.create("/api/v1/reports/exports/" + export.id()))
                        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);
            }
        };
    }

    /** A background export's status; {@code Retry-After} until it is DONE or FAILED. */
    @GetMapping("/exports/{id}")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The export job"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)")
    })
    @RequiresBothPermissions(value = Permission.REPORT_READ, and = Permission.EXCEPTION_READ)
    ResponseEntity<ExportView> export(@PathVariable UUID id) {
        ExportView export = exports.status(id);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (!export.isTerminal()) {
            ok.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return ok.body(export);
    }

    /** A finished background export's CSV, streamed from object storage; {@code 409} until DONE. */
    @GetMapping("/exports/{id}/file")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The exported CSV"),
        @ApiResponse(responseCode = "404", description = "not found (or another tenant's id)"),
        @ApiResponse(
                responseCode = "409",
                description = "conflict: the code in the problem detail says which (api-design.md §3.1)")
    })
    @RequiresBothPermissions(value = Permission.REPORT_READ, and = Permission.EXCEPTION_READ)
    ResponseEntity<StreamingResponseBody> exportFile(@PathVariable UUID id) {
        String key = exports.fileKey(id); // checks organization and status before anything streams
        StreamingResponseBody body = out -> storage.copyTo(key, out);
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment("exceptions-export-" + id + ".csv"))
                .body(body);
    }

    /** {@code attachment}, so a browser downloads the file instead of showing it. */
    private static String attachment(String fileName) {
        return ContentDisposition.attachment().filename(fileName).build().toString();
    }
}
