package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionCase;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionCaseRepository;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionNote;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionNoteRepository;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStateMachine;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import com.reconflow.paymentreconflow.exceptions.internal.domain.InvalidTransitionException;
import com.reconflow.paymentreconflow.exceptions.internal.domain.Transition;
import com.reconflow.paymentreconflow.exceptions.internal.domain.TransitionInput;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.error.UniqueViolations;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.hibernate.StaleStateException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The versioned writes on a case (ADR 0007). Each one, in one transaction:
 *
 * <ol>
 *   <li>load the case by {@code (id, organization_id)}: another tenant's is a 404;</li>
 *   <li>compare its version with the client's {@code If-Match} explicitly. This catches the
 *       <em>slow</em> race: Bob read version 3 minutes ago, Alice has since written version 4.
 *       {@code @Version} alone can't, because this request loaded the case fresh, at version 4;</li>
 *   <li>apply the change through the state machine (history and audit in the same transaction);</li>
 *   <li>flush, so {@code UPDATE ... WHERE version = 3} runs here. That catches the <em>fast</em> race:
 *       two requests that both passed step 2 in the same instant. The second UPDATE waits for the
 *       first's row lock, then matches no row.</li>
 * </ol>
 *
 * Both races answer {@code 409 stale-version} with the current version, read in a new transaction
 * (the failed one is rolled back). A TransactionTemplate rather than {@code @Transactional}, so the
 * failure can be caught <em>outside</em> the transaction it aborted.
 */
@Service
public class ExceptionCommands {

    static final String ACTIVE_CASE_UK = "exception_case_active_uk";

    private final ExceptionCaseRepository cases;
    private final ExceptionStateMachine machine;
    private final ExceptionAssignments assignments;
    private final ExceptionWriteHook hook;
    private final TenantContext tenant;
    private final TransactionTemplate tx;
    private final EntityManager em;
    private final ExceptionNoteRepository notes;
    private final AuditService audit;
    private final CurrentUser currentUser;

    ExceptionCommands(
            ExceptionCaseRepository cases,
            ExceptionStateMachine machine,
            ExceptionAssignments assignments,
            ExceptionWriteHook hook,
            TenantContext tenant,
            TransactionTemplate tx,
            EntityManager em,
            ExceptionNoteRepository notes,
            AuditService audit,
            CurrentUser currentUser) {
        this.cases = cases;
        this.machine = machine;
        this.assignments = assignments;
        this.hook = hook;
        this.tenant = tenant;
        this.tx = tx;
        this.em = em;
        this.notes = notes;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    /**
     * Adds an investigation note, in any status (a post-resolution "refund confirmed" is a fair
     * note). No If-Match and no version bump: a note is a new row in another table and changes
     * nothing on the case, so it can't conflict with anyone's edit, and two people adding notes at
     * once both succeed. The case is still loaded first, so a note on another tenant's case is a
     * 404, not a foreign-key error.
     */
    public UUID addNote(UUID id, String body) {
        UUID org = tenant.organizationId();
        // Defense in depth, as in the state machine: the endpoint's annotation is the first guard.
        if (!tenant.has(Permission.EXCEPTION_UPDATE)) {
            throw new AccessDeniedException("Missing permission " + Permission.EXCEPTION_UPDATE);
        }
        return Objects.requireNonNull(tx.execute(s -> {
            cases.findByIdAndOrganizationId(id, org).orElseThrow(() -> new NotFoundException("Exception", id));
            ExceptionNote saved = notes.save(ExceptionNote.create(org, id, currentUser.id(), body));
            // The body isn't copied: it is stored immutably already, and audit payloads stay small.
            audit.record(AuditEntry.of(AuditAction.EXCEPTION_NOTE_ADDED, AuditEntityType.EXCEPTION_CASE, id)
                    .with("noteId", saved.getId()));
            return saved.getId();
        }));
    }

    /**
     * Assign/unassign and/or move between OPEN and IN_REVIEW. Assigning to the current assignee is a
     * no-op (no audit, no version bump), but the version must still match: the client acted on what
     * it saw.
     */
    public void patch(UUID id, long expectedVersion, ExceptionPatch patch) {
        write(id, expectedVersion, c -> {
            if (patch.hasAssignee()) {
                assignments.assign(c, patch.assignee());
            }
            if (patch.status() != null) {
                machine.apply(c, transitionFor(c.getStatus(), patch.status()), TransitionInput.reason(patch.reason()));
            }
        });
    }

    /**
     * OPEN or IN_REVIEW → RESOLVED with a code and, where the code needs one, a note
     * (reconciliation-rules.md §5.3). Resolving doesn't unassign: the assignee is part of the record
     * of who worked the case. Any user with EXCEPTION_RESOLVE may resolve, assignee or not (v1).
     */
    public void resolve(UUID id, long expectedVersion, @Nullable String code, @Nullable String note) {
        write(id, expectedVersion, c -> machine.apply(c, Transition.RESOLVE, TransitionInput.resolution(code, note)));
    }

    /**
     * RESOLVED → OPEN, by an org admin, with a reason. The previous resolution is cleared from the
     * case (V1's CHECK) and kept on the history row (M7.4); the assignee stays, as the natural owner.
     *
     * <p>The active-case collision (M7.3), in two layers, like the duplicate upload of M5:
     * <ol>
     *   <li>a friendly pre-check: another active case with the same key → 409 with its id;</li>
     *   <li>the guarantee: if a run inserts one between the pre-check and our UPDATE, the partial
     *       unique index {@code exception_case_active_uk} fails the flush. PostgreSQL has then
     *       aborted the transaction, so the existing case is looked up in a <em>new</em> one.</li>
     * </ol>
     */
    public void reopen(UUID id, long expectedVersion, @Nullable String reason) {
        UUID org = tenant.organizationId();
        try {
            write(id, expectedVersion, c -> {
                // Only a RESOLVED case can collide; anything else is the state machine's 409.
                if (c.getStatus() == ExceptionStatus.RESOLVED) {
                    cases.findActiveIdByOrganizationIdAndKey(org, c.getDiscrepancyKey())
                            .ifPresent(active -> {
                                throw new ActiveCaseExistsException(active);
                            });
                }
                machine.apply(c, Transition.REOPEN, TransitionInput.reason(reason));
            });
        } catch (RuntimeException e) {
            if (!UniqueViolations.isViolationOf(e, ACTIVE_CASE_UK)) {
                throw e;
            }
            String key = tx.execute(s -> cases.findByIdAndOrganizationId(id, org)
                    .map(ExceptionCase::getDiscrepancyKey)
                    .orElse(null));
            UUID active = key == null
                    ? null
                    : tx.execute(s ->
                            cases.findActiveIdByOrganizationIdAndKey(org, key).orElse(null));
            throw new ActiveCaseExistsException(active);
        }
    }

    /** PATCH moves only between OPEN and IN_REVIEW; resolve and reopen have their own endpoints. */
    static Transition transitionFor(ExceptionStatus current, ExceptionStatus target) {
        if (current == ExceptionStatus.OPEN && target == ExceptionStatus.IN_REVIEW) {
            return Transition.START_REVIEW;
        }
        if (current == ExceptionStatus.IN_REVIEW && target == ExceptionStatus.OPEN) {
            return Transition.RETURN_TO_OPEN;
        }
        String hint = current == ExceptionStatus.RESOLVED ? " Reopen it with POST .../reopen." : "";
        throw new InvalidTransitionException(
                current, "A case that is " + current + " can't be moved to " + target + "." + hint);
    }

    private void write(UUID id, long expectedVersion, Consumer<ExceptionCase> change) {
        UUID org = tenant.organizationId();
        try {
            tx.executeWithoutResult(s -> {
                ExceptionCase c = cases.findByIdAndOrganizationId(id, org)
                        .orElseThrow(() -> new NotFoundException("Exception", id));
                if (c.getVersion() != expectedVersion) {
                    throw new StaleVersionException(c.getVersion()); // the slow race
                }
                hook.afterVersionCheck(id);
                change.accept(c);
                em.flush(); // UPDATE ... WHERE version = :expected runs here: the fast race
                hook.beforeCommit(id);
            });
        } catch (OptimisticLockException | StaleStateException | OptimisticLockingFailureException e) {
            throw new StaleVersionException(currentVersion(id, org));
        }
    }

    private long currentVersion(UUID id, UUID org) {
        Long version = tx.execute(s -> cases.findByIdAndOrganizationId(id, org)
                .map(ExceptionCase::getVersion)
                .orElseThrow(() -> new NotFoundException("Exception", id)));
        return version == null ? 0 : version;
    }
}
