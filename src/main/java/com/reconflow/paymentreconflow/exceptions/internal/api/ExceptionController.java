package com.reconflow.paymentreconflow.exceptions.internal.api;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionCommands;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionDetailView.NoteView;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionFilter;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionList;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionPatch;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionQueries;
import com.reconflow.paymentreconflow.exceptions.internal.application.ExceptionSummaryView;
import com.reconflow.paymentreconflow.exceptions.internal.application.IfMatch;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * {@code /api/v1/exceptions} (api-design.md §8.13–§8.18). The organization comes from
 * TenantContext, never from the request. Under {@code internal}: Modulith restricts Java type
 * dependencies between modules, and nothing outside should call a controller.
 */
@RestController
@RequestMapping("/api/v1/exceptions")
class ExceptionController {

    static final String MERGE_PATCH_JSON = "application/merge-patch+json";

    private final ExceptionQueries queries;
    private final ExceptionList list;
    private final ExceptionCommands commands;

    ExceptionController(ExceptionQueries queries, ExceptionList list, ExceptionCommands commands) {
        this.queries = queries;
        this.list = list;
        this.commands = commands;
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

    /**
     * Assign ({@code assignedTo}: a user id, or null to unassign) and/or move between OPEN and
     * IN_REVIEW ({@code status}, with {@code reason} for IN_REVIEW → OPEN). JSON Merge Patch only:
     * {@code consumes} makes a plain {@code application/json} PATCH a 415 rather than silently
     * accepting a body whose null-means-clear semantics the client may not intend.
     *
     * <p>If-Match is parsed before the body is applied: missing is 428, malformed 400.
     */
    @PatchMapping(path = "/{id}", consumes = MERGE_PATCH_JSON)
    @RequiresPermission(Permission.EXCEPTION_UPDATE)
    ResponseEntity<ExceptionDetailView> patch(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
            @RequestBody JsonNode body) {
        long expected = IfMatch.parseRequired(ifMatch);
        commands.patch(id, expected, ExceptionPatch.from(body));
        return withETag(queries.detail(id));
    }

    /** Not trimmed or cleaned beyond rejecting blank: a note is evidence. */
    record NoteRequest(@NotBlank @Size(max = 4000) String body) {}

    /**
     * 201 with the note: it is complete when the response is sent. Needs no If-Match (the case row
     * doesn't change). The Location names the note; notes are read through the case's detail view.
     */
    @PostMapping("/{id}/notes")
    @RequiresPermission(Permission.EXCEPTION_UPDATE)
    ResponseEntity<NoteView> addNote(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        NoteView note = queries.note(id, commands.addNote(id, request.body()));
        return ResponseEntity.created(URI.create("/api/v1/exceptions/" + id + "/notes/" + note.id()))
                .body(note);
    }

    /**
     * The code is a string, validated by the state machine (an unknown code is a 400 field error,
     * not a JSON parse failure), which also applies the code-dependent "note required" rule.
     */
    record ResolveRequest(
            @Nullable String resolutionCode,
            @Nullable @Size(max = 4000) String note) {}

    /**
     * A command, not {@code PATCH {status: RESOLVED}}: resolving has its own permission, its own
     * required inputs and its own audit event, which a generic patch handler would bury.
     */
    @PostMapping("/{id}/resolve")
    @RequiresPermission(Permission.EXCEPTION_RESOLVE)
    ResponseEntity<ExceptionDetailView> resolve(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch,
            @Valid @RequestBody ResolveRequest request) {
        long expected = IfMatch.parseRequired(ifMatch);
        commands.resolve(id, expected, request.resolutionCode(), request.note());
        return withETag(queries.detail(id));
    }

    static ResponseEntity<ExceptionDetailView> withETag(ExceptionDetailView view) {
        return ResponseEntity.ok().eTag(Long.toString(view.version())).body(view);
    }
}
