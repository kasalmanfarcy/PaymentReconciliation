package com.reconflow.paymentreconflow.shared.jobs;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs something once the current transaction has committed (M9.9's metrics): a counter bumped
 * inside a transaction that then rolls back (a retried chunk, a lost lease) would count work that
 * never happened. Outside a transaction it runs at once.
 */
public final class AfterCommit {

    private AfterCommit() {}

    public static void run(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
