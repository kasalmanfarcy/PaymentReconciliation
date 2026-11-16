package com.reconflow.paymentreconflow.security.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.organization.OrganizationService;
import com.reconflow.paymentreconflow.security.user.AppUserRepository;
import com.reconflow.paymentreconflow.security.user.MembershipRepository;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The seeder is off in the test profile (tests make their own fixtures), so it is built by hand.
 * It uses fixed IDs and emails, so this is the only test that may run it.
 */
class LocalSeederIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    @Autowired
    AppUserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    TenantScope tenantScope;

    @Autowired
    TransactionTemplate tx;

    @Test
    void seedsOnceAndTheSeededAdminCanLogIn() throws Exception {
        var seeder =
                new LocalSeeder("seed-password-for-test", organizations, users, memberships, encoder, tenantScope, tx);

        assertThat(seeder.seed()).isTrue();
        assertThat(seeder.seed()).isFalse(); // a second start seeds nothing

        assertThat(jdbc.queryForObject(
                        "select count(*) from membership where organization_id in (?, ?)",
                        Integer.class,
                        LocalSeeder.ACME_ID,
                        LocalSeeder.GLOBEX_ID))
                .isEqualTo(10); // 4 roles x 2 orgs + the consultant in both
        assertThat(jdbc.queryForObject(
                        "select is_platform_admin from app_user where email = 'platform@reconflow.test'",
                        Boolean.class))
                .isTrue();
        login("admin@acme.test", "seed-password-for-test")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships[0].organizationId").value(LocalSeeder.ACME_ID.toString()))
                .andExpect(jsonPath("$.memberships[0].role").value("ORG_ADMIN"));
        login("consultant@both.test", "seed-password-for-test")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.length()").value(2));
        // Each organization's creation is audited in that organization's own log.
        assertThat(jdbc.queryForObject(
                        "select organization_id from audit_event where action = 'ORGANIZATION_CREATED' and entity_id = ?",
                        java.util.UUID.class,
                        LocalSeeder.GLOBEX_ID))
                .isEqualTo(LocalSeeder.GLOBEX_ID);
    }
}
