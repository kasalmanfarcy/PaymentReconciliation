package com.reconflow.paymentreconflow.exceptions.internal.domain;

import com.reconflow.paymentreconflow.shared.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * One status change of a case: "what happened to this case?" as typed columns, cheap to query and
 * show. {@code audit_event} records the same change for compliance; both are written in the same
 * transaction, so they can't disagree. Append-only ({@link Immutable} plus the V6 triggers).
 *
 * <p>The resolution code and note are copied onto the RESOLVED row <em>and</em> onto a later reopen
 * row (the previous resolution), so clearing them from {@code exception_case} on reopen (V1's CHECK
 * requires it) loses nothing (M7.4). Assignments and notes are not status changes and never appear
 * here; they are audited only.
 */
@Entity
@Table(name = "exception_status_history")
@Immutable
public class ExceptionStatusHistory implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "exception_id", nullable = false, updatable = false)
    private UUID exceptionId;

    /** NULL on the creation row (the engine's {@code NULL → OPEN}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private ExceptionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private ExceptionStatus toStatus;

    @Column(updatable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_code", updatable = false)
    private ResolutionCode resolutionCode;

    @Column(name = "resolution_note", updatable = false)
    private String resolutionNote;

    /** NULL for system transitions (the engine creating the case). */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    // DB default now(); order by (created_at, id).
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew;

    protected ExceptionStatusHistory() {} // JPA

    /**
     * The row for each kind of transition (phase-M7 §M7.4). Only the resolve row and the reopen row
     * carry a resolution: the code and note that were just set, or the ones a reopen is about to
     * clear. {@code resolved_by}/{@code resolved_at} are not copied: they are the resolve row's
     * {@code actor_id} and {@code created_at}.
     *
     * <table>
     *   <tr><th>Transition</th><th>from</th><th>to</th><th>reason</th><th>resolution</th></tr>
     *   <tr><td>start review</td><td>OPEN</td><td>IN_REVIEW</td><td>—</td><td>—</td></tr>
     *   <tr><td>back to open</td><td>IN_REVIEW</td><td>OPEN</td><td>required</td><td>—</td></tr>
     *   <tr><td>resolve</td><td>OPEN/IN_REVIEW</td><td>RESOLVED</td><td>—</td><td>new code, note</td></tr>
     *   <tr><td>reopen</td><td>RESOLVED</td><td>OPEN</td><td>required</td><td>previous code, note</td></tr>
     * </table>
     */
    static ExceptionStatusHistory statusChange(
            ExceptionCase c, ExceptionStatus from, ExceptionStatus to, @Nullable String reason, UUID actorId) {
        return row(c, from, to, reason, null, null, actorId);
    }

    static ExceptionStatusHistory resolved(
            ExceptionCase c, ExceptionStatus from, ResolutionCode code, @Nullable String note, UUID actorId) {
        return row(c, from, ExceptionStatus.RESOLVED, null, code, note, actorId);
    }

    /**
     * Copying the previous resolution onto the reopen row is redundant (the resolve row has it), but
     * it makes the row self-explanatory: "reopened a case resolved as GATEWAY_FEE because ...".
     */
    static ExceptionStatusHistory reopened(
            ExceptionCase c,
            String reason,
            @Nullable ResolutionCode previousCode,
            @Nullable String previousNote,
            UUID actorId) {
        return row(c, ExceptionStatus.RESOLVED, ExceptionStatus.OPEN, reason, previousCode, previousNote, actorId);
    }

    private static ExceptionStatusHistory row(
            ExceptionCase c,
            ExceptionStatus from,
            ExceptionStatus to,
            @Nullable String reason,
            @Nullable ResolutionCode resolutionCode,
            @Nullable String resolutionNote,
            UUID actorId) {
        var h = new ExceptionStatusHistory();
        h.id = Ids.newId();
        h.organizationId = c.getOrganizationId();
        h.exceptionId = c.getId();
        h.fromStatus = from;
        h.toStatus = to;
        h.reason = reason;
        h.resolutionCode = resolutionCode;
        h.resolutionNote = resolutionNote;
        h.actorId = actorId;
        h.isNew = true;
        return h;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }

    public UUID getExceptionId() {
        return exceptionId;
    }

    public @Nullable ExceptionStatus getFromStatus() {
        return fromStatus;
    }

    public ExceptionStatus getToStatus() {
        return toStatus;
    }

    public @Nullable String getReason() {
        return reason;
    }

    public @Nullable ResolutionCode getResolutionCode() {
        return resolutionCode;
    }

    public @Nullable String getResolutionNote() {
        return resolutionNote;
    }

    public @Nullable UUID getActorId() {
        return actorId;
    }
}
