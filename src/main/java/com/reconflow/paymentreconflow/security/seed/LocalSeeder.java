package com.reconflow.paymentreconflow.security.seed;

import com.reconflow.paymentreconflow.organization.OrganizationService;
import com.reconflow.paymentreconflow.security.user.AppUser;
import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import com.reconflow.paymentreconflow.security.user.Membership;
import com.reconflow.paymentreconflow.security.user.MembershipRepository;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Local demo data (M3.8): two organizations, a user per role in each, one user in both, and a
 * platform admin.
 *
 * <p>Since M10.1 the accounts themselves (passwords included) are in the local Keycloak realm,
 * {@code infra/keycloak/realm-reconflow.json}, with fixed ids. This seeds the ReconFlow side: the
 * organizations, memberships and {@code app_user} rows linked to those ids by
 * {@code external_subject}. {@code LocalSeederIT} checks the two files agree.
 *
 * <p>A Java seeder rather than a Flyway {@code R__} script: a script in {@code db/migration} would
 * also run in production, and going through the same services as real code (OrganizationService and
 * its audit row, TenantScope for RLS) shapes the data exactly like real data.
 *
 * <p>On only where {@code reconflow.seed.enabled=true}, which only application-local.yml sets.
 * Tests create their own fixtures and never depend on seed data. Idempotent: does nothing once
 * {@code admin@acme.test} exists.
 */
@Component
@ConditionalOnBooleanProperty("reconflow.seed.enabled")
public class LocalSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSeeder.class);

    /** Fixed, documented IDs so API collections (M11.8) can send them in X-Organization-Id. */
    public static final UUID ACME_ID = UUID.fromString("0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0e");

    public static final UUID GLOBEX_ID = UUID.fromString("0193b9e2-7c41-7a10-8d2e-5f6a7b8c9d0f");

    public static final String MARKER_EMAIL = "admin@acme.test";

    /** Email → Keycloak user id, exactly as in infra/keycloak/realm-reconflow.json. */
    public static final Map<String, String> SUBJECTS = Map.ofEntries(
            Map.entry("admin@acme.test", "0193b9e2-7c41-7a10-8d2e-000000000a01"),
            Map.entry("investigator@acme.test", "0193b9e2-7c41-7a10-8d2e-000000000a02"),
            Map.entry("uploader@acme.test", "0193b9e2-7c41-7a10-8d2e-000000000a03"),
            Map.entry("viewer@acme.test", "0193b9e2-7c41-7a10-8d2e-000000000a04"),
            Map.entry("admin@globex.test", "0193b9e2-7c41-7a10-8d2e-000000000b01"),
            Map.entry("investigator@globex.test", "0193b9e2-7c41-7a10-8d2e-000000000b02"),
            Map.entry("uploader@globex.test", "0193b9e2-7c41-7a10-8d2e-000000000b03"),
            Map.entry("viewer@globex.test", "0193b9e2-7c41-7a10-8d2e-000000000b04"),
            Map.entry("consultant@both.test", "0193b9e2-7c41-7a10-8d2e-000000000c01"),
            Map.entry("platform@reconflow.test", "0193b9e2-7c41-7a10-8d2e-000000000d01"));

    private final OrganizationService organizations;
    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;

    public LocalSeeder(
            OrganizationService organizations,
            AppUserRepository users,
            MembershipRepository memberships,
            TenantScope tenantScope,
            TransactionTemplate tx) {
        this.organizations = organizations;
        this.users = users;
        this.memberships = memberships;
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
        UUID consultant = tenantScope.call(
                ACME_ID,
                () -> tx.execute(s -> {
                    seedOrganization(ACME_ID, "Acme Payments", "acme.test");
                    UUID id = user("consultant@both.test", "Consultant (both orgs)");
                    memberships.save(Membership.create(id, ACME_ID, Role.INVESTIGATOR, null));
                    return id;
                }));
        // Each organization in its own transaction, bound to that organization, so its audit row
        // passes row-level security.
        tenantScope.run(
                GLOBEX_ID,
                () -> tx.executeWithoutResult(s -> {
                    seedOrganization(GLOBEX_ID, "Globex Retail", "globex.test");
                    memberships.save(Membership.create(consultant, GLOBEX_ID, Role.VIEWER, null));
                }));
        tx.executeWithoutResult(s -> {
            AppUser platform = AppUser.create(
                    "platform@reconflow.test", "Platform admin", SUBJECTS.get("platform@reconflow.test"));
            platform.grantPlatformAdmin();
            users.save(platform);
        });
        log.warn(
                "Seeded local data: organizations Acme Payments ({}) and Globex Retail ({}); users"
                        + " admin|investigator|uploader|viewer@acme.test and @globex.test,"
                        + " consultant@both.test, platform@reconflow.test. Log in through the local Keycloak"
                        + " realm (infra/keycloak/realm-reconflow.json; demo password documented in the README).",
                ACME_ID,
                GLOBEX_ID);
        return true;
    }

    private void seedOrganization(UUID id, String name, String domain) {
        organizations.create(id, name, null);
        for (Role role : Role.values()) {
            String local = role == Role.ORG_ADMIN ? "admin" : role.name().toLowerCase(Locale.ROOT);
            UUID userId = user(local + "@" + domain, capitalize(local) + " (" + name + ")");
            memberships.save(Membership.create(userId, id, role, null));
        }
    }

    private UUID user(String email, String displayName) {
        return users.save(AppUser.create(email, displayName, SUBJECTS.get(email)))
                .getId();
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
