package com.reconflow.paymentreconflow.exceptions.internal.application;

import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionCase;
import com.reconflow.paymentreconflow.exceptions.internal.domain.ExceptionStateMachine;
import com.reconflow.paymentreconflow.security.directory.UserDirectory;
import com.reconflow.paymentreconflow.shared.context.Permission;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Who may take a case: an active member of the <em>same</em> organization whose role grants
 * {@code EXCEPTION_UPDATE} (investigators and org admins). Asked of security's user directory, never
 * of the {@code membership} table directly. The change itself is the state machine's.
 */
@Component
class ExceptionAssignments {

    private final UserDirectory users;
    private final ExceptionStateMachine machine;

    ExceptionAssignments(UserDirectory users, ExceptionStateMachine machine) {
        this.users = users;
        this.machine = machine;
    }

    /** @return false if the assignee didn't change (no audit, no version bump) */
    boolean assign(ExceptionCase c, @Nullable UUID assignee) {
        // A RESOLVED case is a 409 whoever the assignee is: the state conflict is checked first, by
        // the state machine; only an assignable case gets the 422 membership check.
        if (assignee != null
                && c.getStatus().isActive()
                && !users.isActiveMemberWith(c.getOrganizationId(), assignee, Permission.EXCEPTION_UPDATE)) {
            throw new InvalidAssigneeException();
        }
        return machine.assign(c, assignee);
    }
}
