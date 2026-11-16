package com.reconflow.paymentreconflow.security.seed;

import com.reconflow.paymentreconflow.organization.OrganizationService;
import com.reconflow.paymentreconflow.security.user.AppUser;
import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import com.reconflow.paymentreconflow.security.user.Membership;
import com.reconflow.paymentreconflow.security.user.MembershipRepository;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Local demo data (M3.8): two organizations, a user per role in each, one user in both, and a
 * platform admin, all with the same documented password and no pending password change (or every
 * demo would dead-end at change-password).
 *
 * <p>A Java seeder rather than a Flyway {@code R__} script: passwords must be bcrypt-hashed (a SQL
 * seed would commit hashes of known passwords into the migration folder), a script in
 * {@code db/migration} would also run in production, and going through the same services as real
 * code (OrganizationService and its audit row, TenantScope for RLS) shapes the data exactly like
 * real data.
 *
 * <p>On only where {@code reconflow.seed.enabled=true}, which only application-local.yml sets:
 * profile names stay in YAML, as for the worker role. Tests create their own fixtures and never
 * depend on seed data. Idempotent: does nothing once {@code admin@acme.test} exists.
 */
@Component
@ConditionalOnBooleanProperty("reconflow.seed.enabled")
public class LocalSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSeeder.class);

    /** Fixed, documented IDs so API collections (M11.8) can send them in X-Organization-Id. */
    public static final UUID ACME_ID = UUID.fromString("0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0e");

    public static final UUID GLOBEX_ID = UUID.fromString("0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0f");

    public static final String MARKER_EMAIL = "admin@acme.test";

    private final String password;
    private final OrganizationService organizations;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final PasswordEncoder encoder;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;

    public LocalSeeder(
            @Value("${reconflow.seed.password}") String password,
            OrganizationService organizations,
            AppUserRepository users,
            MembershipRepository memberships,
            PasswordEncoder encoder,
            TenantScope tenantScope,
            TransactionTemplate tx) {
        this.password = password;
        this.organizations = organizations;
        this.users = users;
        this.memberships = memberships;
        this.encoder = encoder;
        this.tenantScope = tenantScope;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** True when it seeded, false when the data was already there. */
    public boolean seed() {
        if (users.findByEmailIgnoreCase(MARKER_EMAIL).isPresent()) {
            log.info("Local seed data already present");
            return false;
        }
        String hash = encoder.encode(password); // once: bcrypt costs ~200 ms per call
        UUID consultant = tenantScope.call(
                ACME_ID,
                () -> tx.execute(s -> {
                    seedOrganization(ACME_ID, "Acme Payments", "acme.test", hash);
                    UUID id = user("consultant@both.test", "Consultant (both orgs)", hash);
                    memberships.save(Membership.create(id, ACME_ID, Role.INVESTIGATOR, null));
                    return id;
                }));
        // Each organization in its own transaction, bound to that organization, so its audit row
        // passes row-level security.
        tenantScope.run(
                GLOBEX_ID,
                () -> tx.executeWithoutResult(s -> {
                    seedOrganization(GLOBEX_ID, "Globex Retail", "globex.test", hash);
                    memberships.save(Membership.create(consultant, GLOBEX_ID, Role.VIEWER, null));
                }));
        tx.executeWithoutResult(s -> {
            AppUser platform = AppUser.create("platform@reconflow.test", "Platform admin", hash, false);
            platform.grantPlatformAdmin();
            users.save(platform);
        });
        log.warn(
                "Seeded local data: organizations Acme Payments ({}) and Globex Retail ({}); users"
                        + " admin|investigator|uploader|viewer@acme.test and @globex.test,"
                        + " consultant@both.test, platform@reconflow.test; password for all: {}",
                ACME_ID,
                GLOBEX_ID,
                password);
        return true;
    }

    private void seedOrganization(UUID id, String name, String domain, String hash) {
        organizations.create(id, name, null);
        for (Role role : Role.values()) {
            String local = role == Role.ORG_ADMIN ? "admin" : role.name().toLowerCase(Locale.ROOT);
            UUID userId = user(local + "@" + domain, capitalize(local) + " (" + name + ")", hash);
            memberships.save(Membership.create(userId, id, role, null));
        }
    }

    private UUID user(String email, String displayName, String hash) {
        return users.save(AppUser.create(email, displayName, hash, false)).getId();
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
