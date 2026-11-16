package com.reconflow.paymentreconflow.exceptions.internal.domain;

import static com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus.IN_REVIEW;
import static com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus.OPEN;
import static com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus.RESOLVED;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.shared.context.Permission;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The exception case state machine as data (reconciliation-rules.md §7.3): every allowed status
 * change, who may make it, what it needs, and what it audits. Anything not in this table is
 * forbidden, so "resolve a resolved case" can't happen: RESOLVED isn't a {@code from} of RESOLVE.
 *
 * <p>Creation ({@code → OPEN}) isn't here: only the engine creates cases, through
 * DiscrepancyRecorder. Assigning and adding notes don't change the status, so they aren't
 * transitions either.
 */
public enum Transition {
    START_REVIEW(
            EnumSet.of(OPEN),
            IN_REVIEW,
            Permission.EXCEPTION_UPDATE,
            ReasonRule.NONE,
            AuditAction.EXCEPTION_STATUS_CHANGED),
    RETURN_TO_OPEN(
            EnumSet.of(IN_REVIEW),
            OPEN,
            Permission.EXCEPTION_UPDATE,
            ReasonRule.REQUIRED,
            AuditAction.EXCEPTION_STATUS_CHANGED),
    /** Directly from OPEN too: a known timing difference shouldn't need a "start review" click first. */
    RESOLVE(
            EnumSet.of(OPEN, IN_REVIEW),
            RESOLVED,
            Permission.EXCEPTION_RESOLVE,
            ReasonRule.RESOLUTION,
            AuditAction.EXCEPTION_RESOLVED),
    /** Org admins only: rare, explained and attributable. */
    REOPEN(
            EnumSet.of(RESOLVED),
            OPEN,
            Permission.EXCEPTION_REOPEN,
            ReasonRule.REQUIRED,
            AuditAction.EXCEPTION_REOPENED);

    private final Set<ExceptionStatus> from;
    private final ExceptionStatus to;
    private final Permission permission;
    private final ReasonRule reasonRule;
    private final AuditAction auditAction;

    Transition(
            Set<ExceptionStatus> from,
            ExceptionStatus to,
            Permission permission,
            ReasonRule reasonRule,
            AuditAction auditAction) {
        this.from = from;
        this.to = to;
        this.permission = permission;
        this.reasonRule = reasonRule;
        this.auditAction = auditAction;
    }

    public boolean allowedFrom(ExceptionStatus status) {
        return from.contains(status);
    }

    public ExceptionStatus to() {
        return to;
    }

    public Permission permission() {
        return permission;
    }

    public ReasonRule reasonRule() {
        return reasonRule;
    }

    public AuditAction auditAction() {
        return auditAction;
    }

    /** The transitions a case in this status may take; the 409 body lists them. */
    public static List<Transition> availableFrom(ExceptionStatus status) {
        return Arrays.stream(values()).filter(t -> t.allowedFrom(status)).toList();
    }
}
