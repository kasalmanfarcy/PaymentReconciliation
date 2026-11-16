package com.reconflow.paymentreconflow.exceptions.internal.domain;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that changes an exception case (reconciliation-rules.md §7.3): every status
 * transition and every assignment. Each change checks the {@link Transition} table, the permission
 * and the reason rule, then writes the history row (status changes) and the audit event in the
 * caller's transaction ({@link Propagation#MANDATORY}), so the change, its history and its audit
 * commit or roll back together.
 *
 * <p>Lives in {@code domain}, next to {@link ExceptionCase}, so the entity's mutators can stay
 * package-private: the compiler enforces "one place", and ArchitectureRulesTest names it.
 *
 * <p>No I/O on the case itself: the command service loads it (tenant-scoped), compares
 * {@code If-Match}, calls this, and flushes. That keeps this class unit-testable with plain objects.
 *
 * <p><b>Order of checks</b>: transition, then permission, then reason. Checking the transition first
 * tells a caller without the permission the case's status, which is harmless here: every role holds
 * {@code EXCEPTION_READ}. The permission check repeats the controller's {@code @RequiresPermission}
 * on purpose: that guards the endpoint, this guards the transition, so a future endpoint that
 * forgets the annotation still can't reopen a case.
 */
@Component
public class ExceptionStateMachine {

    private final ExceptionHistoryRepository history;
    private final AuditService audit;
    private final TenantContext tenant;
    private final CurrentUser currentUser;
    private final Clock clock;

    public ExceptionStateMachine(
            ExceptionHistoryRepository history,
            AuditService audit,
            TenantContext tenant,
            CurrentUser currentUser,
            Clock clock) {
        this.history = history;
        this.audit = audit;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Applies one transition to a loaded, tenant-checked case.
     *
     * @throws InvalidTransitionException (409) if the table doesn't allow it from the current status
     * @throws AccessDeniedException (403) without the transition's permission
     * @throws com.reconflow.paymentreconflow.shared.error.ValidationFailedException (400) on a
     *     missing or invalid reason, code or note
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(ExceptionCase c, Transition t, TransitionInput in) {
        ExceptionStatus from = c.getStatus();
        if (!t.allowedFrom(from)) {
            throw InvalidTransitionException.of(from, t);
        }
        requirePermission(t.permission());
        ResolutionCode code = t.reasonRule().validate(in);

        UUID actor = currentUser.id();
        ResolutionCode previousCode = c.getResolutionCode();
        String previousNote = c.getResolutionNote();
        String reason = t.reasonRule() == ReasonRule.REQUIRED ? in.reason() : null;
        // The version this write produces: @Version bumps it once at flush, however many fields change.
        long nextVersion = c.getVersion() + 1;

        AuditEntry entry = AuditEntry.of(t.auditAction(), AuditEntityType.EXCEPTION_CASE, c.getId())
                .with("from", from.name())
                .with("to", t.to().name());
        switch (t) {
            case RESOLVE -> {
                String note = ReasonRule.isBlank(in.note()) ? null : in.note();
                c.markResolved(Objects.requireNonNull(code), note, actor, clock.instant());
                c.moveTo(t.to());
                history.save(ExceptionStatusHistory.resolved(c, from, code, note, actor));
                // The note isn't copied: it is stored immutably in history, and audit payloads stay small.
                entry = entry.with("resolutionCode", code.name());
            }
            case REOPEN -> {
                c.clearResolution(); // V1's CHECK: resolution fields are set exactly while RESOLVED
                c.moveTo(t.to());
                history.save(ExceptionStatusHistory.reopened(c, reason, previousCode, previousNote, actor));
                entry = entry.with("reason", reason)
                        .with("previousResolutionCode", previousCode == null ? null : previousCode.name());
            }
            case START_REVIEW, RETURN_TO_OPEN -> {
                c.moveTo(t.to());
                history.save(ExceptionStatusHistory.statusChange(c, from, t.to(), reason, actor));
                entry = entry.with("reason", reason);
            }
        }
        audit.record(entry.with("version", nextVersion));
    }

    /**
     * Assigns or unassigns (null). Not a transition (the status doesn't change), so no history row,
     * but audited ({@code EXCEPTION_ASSIGNED}). The caller has already checked that the assignee is
     * a member who may work cases (ExceptionAssignments).
     *
     * @return false if the assignee is unchanged: a no-op, with no audit event and no version bump
     * @throws InvalidTransitionException (409) on a RESOLVED case: reopen it first
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean assign(ExceptionCase c, @Nullable UUID assignee) {
        if (!c.getStatus().isActive()) {
            throw new InvalidTransitionException(c.getStatus(), "A RESOLVED case can't be assigned; reopen it first.");
        }
        requirePermission(Permission.EXCEPTION_UPDATE);
        UUID previous = c.getAssignedTo();
        if (Objects.equals(previous, assignee)) {
            return false;
        }
        c.assignTo(assignee);
        audit.record(AuditEntry.of(AuditAction.EXCEPTION_ASSIGNED, AuditEntityType.EXCEPTION_CASE, c.getId())
                .with("from", previous)
                .with("to", assignee)
                .with("version", c.getVersion() + 1));
        return true;
    }

    private void requirePermission(Permission permission) {
        if (!tenant.has(permission)) {
            throw new AccessDeniedException("Missing permission " + permission);
        }
    }
}
