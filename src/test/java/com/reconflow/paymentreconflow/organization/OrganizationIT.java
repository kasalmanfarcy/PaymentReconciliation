package com.reconflow.paymentreconflow.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class OrganizationIT extends AbstractIntegrationTest {

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    EntityManagerFactory emf;

    @Autowired
    TransactionTemplate tx;

    @Test
    void persistsAndReloads() {
        Organization saved = tx.execute(status -> organizations.save(Organization.create("Acme Payments")));

        Organization loaded = organizations.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("Acme Payments");
        assertThat(loaded.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(loaded.getCreatedAt()).isNotNull(); // DB default
        assertThat(loaded.isNew()).isFalse(); // @PostLoad
    }

    @Test
    void saveOfANewOrganizationIsOneInsertWithNoSelectFirst() {
        // Without Persistable.isNew(), save() sees a non-null assigned ID, calls merge(), and
        // merge() SELECTs the row before inserting it: two statements instead of one.
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            tx.executeWithoutResult(status -> organizations.save(Organization.create("Globex Retail")));

            assertThat(stats.getEntityInsertCount()).isEqualTo(1);
            assertThat(stats.getEntityLoadCount()).isZero();
            assertThat(stats.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }
}
