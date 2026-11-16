package com.reconflow.paymentreconflow.security.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.reconflow.paymentreconflow.organization.Organization;
import com.reconflow.paymentreconflow.organization.OrganizationRepository;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class UserAndMembershipIT extends AbstractIntegrationTest {

    @Autowired
    AppUserRepository users;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    EntityManagerFactory emf;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aUserWithTwoMembershipsInTwoOrganizationsIsReadBackWithOneQuery() {
        var acme = Organization.create("Acme");
        var globex = Organization.create("Globex");
        var user = AppUser.create("Consultant." + acme.getId() + "@Both.test", "Consultant", null);
        tx.executeWithoutResult(status -> {
            organizations.save(acme);
            organizations.save(globex);
            users.save(user);
            memberships.save(Membership.create(user.getId(), acme.getId(), Role.INVESTIGATOR, null));
            memberships.save(Membership.create(user.getId(), globex.getId(), Role.VIEWER, null));
        });

        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            List<Membership> found = memberships.findByIdUserId(user.getId());

            assertThat(found)
                    .extracting(Membership::getOrganizationId, Membership::getRole)
                    .containsExactlyInAnyOrder(
                            tuple(acme.getId(), Role.INVESTIGATOR), tuple(globex.getId(), Role.VIEWER));
            assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    @Test
    void emailLookupIgnoresCase() {
        var user = AppUser.create("Mixed.Case." + System.nanoTime() + "@Example.test", "M", "kc-" + System.nanoTime());
        tx.executeWithoutResult(status -> users.save(user));

        assertThat(users.findByEmailIgnoreCase(user.getEmail().toLowerCase()))
                .get()
                .extracting(AppUser::getId)
                .isEqualTo(user.getId());
        AppUser loaded = users.findById(user.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(loaded.getExternalSubject()).startsWith("kc-");
        assertThat(loaded.isPlatformAdmin()).isFalse();
    }
}
