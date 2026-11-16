package com.reconflow.paymentreconflow.security.tenant;

import com.reconflow.paymentreconflow.shared.context.TenantContext;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * Tells PostgreSQL which tenant every transaction is for, so the row-level security policies of V4
 * apply (M3.5): {@code set_config('app.org_id', <org>, true)} right after each transaction begins.
 *
 * <p>Why a transaction listener and not the alternatives:
 * <ul>
 *   <li>An aspect around {@code @Transactional} misses transactions Spring Data's own repository
 *       methods start, and depends on fragile ordering with the transaction proxy.</li>
 *   <li>A connection-checkout hook runs per connection, not per transaction: a session-level SET
 *       would leak the tenant to the next borrower of a pooled connection the first time a reset
 *       is missed, and SET LOCAL outside a transaction does nothing.</li>
 *   <li>This fires after <em>every</em> new transaction begins, however it was started, and the
 *       third argument {@code true} makes it SET LOCAL: commit or rollback wipes it, so the next
 *       borrower of the connection can't inherit it. One extra round trip per transaction.</li>
 * </ul>
 *
 * <p>JdbcTemplate gets the connection already bound to the JPA transaction (DataSourceUtils), so
 * the setting lands on the same connection as the queries that follow. Boot adds every
 * TransactionExecutionListener bean to the auto-configured transaction manager.
 *
 * <p>Rules that follow: tenant data is only touched inside a transaction (outside one, RLS sees no
 * tenant and returns nothing, which fails closed), and the tenant is bound <em>before</em> the
 * transaction starts (TenantScope refuses otherwise).
 */
@Component
class TenantTransactionListener implements TransactionExecutionListener {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    TenantTransactionListener(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    public void afterBegin(TransactionExecution transaction, @Nullable Throwable beginFailure) {
        if (beginFailure != null) {
            return;
        }
        tenant.organizationIdIfPresent()
                .ifPresent(org ->
                        jdbc.queryForObject("SELECT set_config('app.org_id', ?, true)", String.class, org.toString()));
    }
}
