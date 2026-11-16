package com.reconflow.paymentreconflow.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.shared.context.NoTenantException;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ScopedTenantHolderTest {

    private final ScopedTenantHolder holder = new ScopedTenantHolder();
    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void nothingIsBoundOutsideAScope() {
        assertThat(holder.organizationIdIfPresent()).isEmpty();
        assertThat(holder.idIfPresent()).isEmpty();
        assertThat(holder.has(Permission.IMPORT_READ)).isFalse();
        assertThatThrownBy(holder::organizationId).isInstanceOf(NoTenantException.class);
        assertThatThrownBy(holder::id).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exposesTheBoundRequestContext() {
        var bound = new ScopedTenantHolder.Bound(user, org, Role.VIEWER, Set.of(Permission.IMPORT_READ));

        ScopedValue.where(ScopedTenantHolder.CURRENT, bound).run(() -> {
            assertThat(holder.organizationId()).isEqualTo(org);
            assertThat(holder.id()).isEqualTo(user);
            assertThat(holder.role()).isEqualTo(Role.VIEWER);
            assertThat(holder.has(Permission.IMPORT_READ)).isTrue();
            assertThat(holder.has(Permission.IMPORT_CREATE)).isFalse();
        });
    }

    @Test
    void tenantScopeBindsAnOrganizationWithASystemActor() {
        UUID seen = holder.call(org, () -> {
            assertThat(holder.idIfPresent()).isEmpty();
            return holder.organizationId();
        });

        assertThat(seen).isEqualTo(org);
        assertThat(holder.organizationIdIfPresent()).isEmpty();
    }

    @Test
    void theBindingEndsEvenWhenTheWorkThrows() {
        assertThatThrownBy(() -> holder.run(org, () -> {
                    throw new IllegalArgumentException("boom");
                }))
                .isInstanceOf(IllegalArgumentException.class);

        // A ThreadLocal set without a finally would still hold org here.
        assertThat(holder.organizationIdIfPresent()).isEmpty();
    }

    @Test
    void anotherThreadDoesNotSeeTheBindingSoItFailsClosed() throws Exception {
        var otherThreadSaw = new java.util.concurrent.atomic.AtomicReference<Object>();
        holder.run(org, () -> {
            Thread t = Thread.ofVirtual().start(() -> otherThreadSaw.set(holder.organizationIdIfPresent()));
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(otherThreadSaw.get()).isEqualTo(java.util.Optional.empty());
    }

    @Test
    void refusesToBindInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> holder.call(org, () -> "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before starting a transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
