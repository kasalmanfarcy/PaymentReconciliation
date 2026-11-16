package com.reconflow.paymentreconflow.security.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The runner only exists when RECONFLOW_BOOTSTRAP_ADMIN_EMAIL is set, so it is built by hand here.
 * The test database is shared and other tests create platform admins, so the "empty environment"
 * case runs inside a transaction that hides them and is rolled back afterwards.
 */
class BootstrapRunnerIT extends AbstractIntegrationTest {

    @Autowired
    AppUserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    TransactionTemplate tx;

    private BootstrapRunner runner(String email) {
        return new BootstrapRunner(email, users, encoder, tx);
    }

    @Test
    void createsTheFirstPlatformAdminOnceInAnEmptyEnvironment() {
        String email = "root-" + UUID.randomUUID() + "@example.test";

        tx.executeWithoutResult(status -> {
            jdbc.update("update app_user set is_platform_admin = false where is_platform_admin");

            assertThat(runner(email).bootstrap()).isTrue();
            // Through the repository: a JPQL query flushes the pending insert first.
            var admin = users.findByEmailIgnoreCase(email).orElseThrow();
            assertThat(admin.isPlatformAdmin()).isTrue();
            assertThat(admin.isMustChangePassword()).isTrue();
            // Idempotent: a second start finds the admin and does nothing.
            assertThat(runner("someone-else-" + email).bootstrap()).isFalse();

            status.setRollbackOnly();
        });
    }

    @Test
    void doesNothingWhenAPlatformAdminExists() {
        data().newPlatformAdmin();
        String email = "late-" + UUID.randomUUID() + "@example.test";

        assertThat(runner(email).bootstrap()).isFalse();
        assertThat(users.findByEmailIgnoreCase(email)).isEmpty();
    }
}
