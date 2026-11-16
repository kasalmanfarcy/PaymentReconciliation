package com.reconflow.paymentreconflow.security.bootstrap;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.security.user.AppUser;
import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import com.reconflow.paymentreconflow.shared.logging.Masked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How a fresh non-local environment gets its first user (authentication.plan §4.6). There is no
 * sign-up, and the local seeder never runs outside {@code local}, so without this nobody could ever
 * create the first organization.
 *
 * <p>Gated on a one-time setting, {@code RECONFLOW_BOOTSTRAP_ADMIN_EMAIL}: if it is set and no
 * platform admin exists yet, an {@code app_user} with that email becomes (or is created as) the
 * platform admin. Since M10.1 it has no password here and the application generates none: the
 * operator creates the account in Keycloak's admin console with that email marked verified, and its
 * first request links the two by verified email (OidcPrincipalResolver). Nothing secret is ever
 * logged. Idempotent: once any platform admin exists it does nothing, so the variable can stay set.
 */
@Component
@ConditionalOnExpression("'${reconflow.bootstrap.admin-email:}' != ''")
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final String adminEmail;
    private final AppUserRepository users;
    private final TransactionTemplate tx;
    private final AuditService audit;

    public BootstrapRunner(
            @Value("${reconflow.bootstrap.admin-email}") String adminEmail,
            AppUserRepository users,
            TransactionTemplate tx,
            AuditService audit) {
        this.adminEmail = adminEmail.trim();
        this.users = users;
        this.tx = tx;
        this.audit = audit;
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
            AppUser admin = users.findByEmailIgnoreCase(adminEmail)
                    .orElseGet(() -> users.save(AppUser.create(adminEmail, "Platform admin", null)));
            admin.grantPlatformAdmin();
            audit.record(AuditEntry.of(AuditAction.PLATFORM_ADMIN_BOOTSTRAPPED, AuditEntityType.USER, admin.getId()));
            log.warn(
                    "Bootstrap: {} is the platform admin. Create that account in Keycloak with the email marked"
                            + " verified; its first request links it. Then unset RECONFLOW_BOOTSTRAP_ADMIN_EMAIL.",
                    Masked.email(adminEmail));
            return true;
        }));
    }
}
