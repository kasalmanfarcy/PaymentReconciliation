package com.reconflow.paymentreconflow.security.bootstrap;

import com.reconflow.paymentreconflow.security.user.AppUser;
import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import com.reconflow.paymentreconflow.security.user.TemporaryPasswords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How a fresh non-local environment gets its first user (authentication.plan §4.6). There is no
 * sign-up, and the local seeder never runs outside {@code local}, so without this nobody could ever
 * log in to create the first organization.
 *
 * <p>Gated on a one-time setting, {@code RECONFLOW_BOOTSTRAP_ADMIN_EMAIL}: if it is set and no
 * platform admin exists yet, create one with a temporary password printed once to the log (the
 * only secret this application ever logs, and only this once), which must be changed at first
 * login. Idempotent: once any platform admin exists it does nothing, so the variable can stay set.
 * The platform admin then creates organizations through the API.
 */
@Component
@ConditionalOnExpression("'${reconflow.bootstrap.admin-email:}' != ''")
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final String adminEmail;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    public BootstrapRunner(
            @Value("${reconflow.bootstrap.admin-email}") String adminEmail,
            AppUserRepository users,
            PasswordEncoder encoder,
            TransactionTemplate tx) {
        this.adminEmail = adminEmail.trim();
        this.users = users;
        this.encoder = encoder;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** True when a platform admin was created (or granted) by this call. */
    public boolean bootstrap() {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (users.existsByPlatformAdminTrue()) {
                log.info("Bootstrap skipped: a platform admin already exists");
                return false;
            }
            var existing = users.findByEmailIgnoreCase(adminEmail);
            if (existing.isPresent()) {
                existing.get().grantPlatformAdmin();
                log.warn("Bootstrap: granted platform admin to the existing user {}", adminEmail);
                return true;
            }
            String temporaryPassword = TemporaryPasswords.generate();
            AppUser admin = AppUser.create(adminEmail, "Platform admin", encoder.encode(temporaryPassword), true);
            admin.grantPlatformAdmin();
            users.save(admin);
            log.warn(
                    "Bootstrap: created platform admin {} with temporary password {} (shown once; change it at"
                            + " first login, then unset RECONFLOW_BOOTSTRAP_ADMIN_EMAIL)",
                    adminEmail,
                    temporaryPassword);
            return true;
        }));
    }
}
