package com.reconflow.paymentreconflow.security.tenant;

import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.NoTenantException;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Implements the three {@code shared} context interfaces on top of one {@link ScopedValue}.
 *
 * <p>Why a ScopedValue (Java 25, JEP 506) instead of a ThreadLocal or a request-scoped bean: it is
 * bound for the duration of a call and unbound when the call returns, even by an exception, so one
 * tenant's ID can never leak into the next request on a pooled thread; and it works identically on
 * HTTP threads (TenantContextFilter) and worker threads ({@link #call}), where request scope does
 * not exist. Work moved to another thread does not see it, which fails closed.
 */
@Component
public class ScopedTenantHolder implements TenantContext, CurrentUser, TenantScope {

    /** What is bound: who, for which organization, with which role and permissions. */
    public record Bound(
            @Nullable UUID userId,
            @Nullable UUID organizationId,
            @Nullable Role role,
            Set<Permission> permissions) {

        public Bound {
            permissions = Set.copyOf(permissions);
        }
    }

    static final ScopedValue<Bound> CURRENT = ScopedValue.newInstance();

    @Override
    public UUID organizationId() {
        return organizationIdIfPresent()
                .orElseThrow(() -> new NoTenantException("No organization is bound to this thread"));
    }

    @Override
    public Optional<UUID> organizationIdIfPresent() {
        return CURRENT.isBound() ? Optional.ofNullable(CURRENT.get().organizationId()) : Optional.empty();
    }

    @Override
    public @Nullable Role role() {
        return CURRENT.isBound() ? CURRENT.get().role() : null;
    }

    @Override
    public boolean has(Permission permission) {
        return CURRENT.isBound() && CURRENT.get().permissions().contains(permission);
    }

    @Override
    public UUID id() {
        return idIfPresent().orElseThrow(() -> new IllegalStateException("No user is bound to this thread"));
    }

    @Override
    public Optional<UUID> idIfPresent() {
        return CURRENT.isBound() ? Optional.ofNullable(CURRENT.get().userId()) : Optional.empty();
    }

    @Override
    public <T> T call(UUID organizationId, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The transaction already began with the old tenant in app.org_id; rebinding now would
            // make Java and the database disagree about the tenant.
            throw new IllegalStateException("Bind the tenant before starting a transaction");
        }
        return ScopedValue.where(CURRENT, new Bound(null, organizationId, null, Set.of()))
                .call(work::get);
    }

    /** Current binding, if any; for security-internal code (the transaction listener, filters). */
    Optional<Bound> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }
}
