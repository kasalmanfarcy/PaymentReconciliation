package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.security.idp.IdentityProvider;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Creates the identity-provider half of a new user inside the caller's database transaction
 * (M10.1). There is no transaction spanning Keycloak and PostgreSQL, so the two halves are made
 * consistent the saga way: Keycloak first, then the {@code app_user} row; if the database
 * transaction rolls back (a unique-email race, the audit insert, anything), a compensating action
 * deletes the account that was just created. An account that already existed in Keycloak is linked,
 * never deleted.
 */
@Component
class AccountProvisioning {

    private static final Logger log = LoggerFactory.getLogger(AccountProvisioning.class);

    /** The IdP subject, and the temporary password when a new account was created (else null). */
    record Provisioned(String subject, @Nullable String temporaryPassword) {}

    private final IdentityProvider idp;

    AccountProvisioning(IdentityProvider idp) {
        this.idp = idp;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Provisioned provision(String email, String displayName) {
        String temporaryPassword = TemporaryPasswords.generate();
        IdentityProvider.Account account = idp.createUser(email.trim(), displayName.trim(), temporaryPassword);
        if (!account.created()) {
            // Someone created the account in Keycloak directly: it keeps its own password.
            return new Provisioned(account.subject(), null);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        idp.delete(account.subject());
                    } catch (RuntimeException e) {
                        // Left for an operator: createUser links to it on the next attempt anyway.
                        log.warn(
                                "Could not delete identity-provider account {} after a rollback", account.subject(), e);
                    }
                }
            }
        });
        return new Provisioned(account.subject(), temporaryPassword);
    }
}
