package com.reconflow.paymentreconflow.shared.context;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Binds an organization for a unit of work outside a tenant request: a worker processing a job
 * row, or a platform admin creating an organization (whose audit row must carry the new org). The
 * actor inside is the system (no {@link CurrentUser}); pass an explicit actor to the audit entry
 * if a person is responsible.
 *
 * <p>Must be called <em>outside</em> any transaction: the database tenant (RLS's
 * {@code app.org_id}) is set when a transaction begins, so binding inside one would leave the
 * database looking at the previous tenant (or none). Implementations reject that.
 */
public interface TenantScope {

    <T> T call(UUID organizationId, Supplier<T> work);

    default void run(UUID organizationId, Runnable work) {
        call(organizationId, () -> {
            work.run();
            return null;
        });
    }
}
