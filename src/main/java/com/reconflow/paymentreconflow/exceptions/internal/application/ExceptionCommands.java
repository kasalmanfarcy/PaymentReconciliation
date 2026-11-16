package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionCase;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionCaseRepository;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStateMachine;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStatus;
import com.reconflow.paymentreconflow.exceptions.internal.domain.InvalidTransitionException;
import com.reconflow.paymentreconflow.exceptions.internal.domain.Transition;
import com.reconflow.paymentreconflow.exceptions.internal.domain.TransitionInput;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.util.UUID;
import java.util.function.Consumer;
import org.hibernate.StaleStateException;
import org.springframework.dao.OptimisticLockingFailureException;
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

    private final ExceptionCaseRepository cases;
    private final ExceptionStateMachine machine;
    private final ExceptionAssignments assignments;
    private final ExceptionWriteHook hook;
    private final TenantContext tenant;
    private final TransactionTemplate tx;
    private final EntityManager em;

    ExceptionCommands(
            ExceptionCaseRepository cases,
            ExceptionStateMachine machine,
            ExceptionAssignments assignments,
            ExceptionWriteHook hook,
            TenantContext tenant,
            TransactionTemplate tx,
            EntityManager em) {
        this.cases = cases;
        this.machine = machine;
        this.assignments = assignments;
        this.hook = hook;
        this.tenant = tenant;
        this.tx = tx;
        this.em = em;
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
