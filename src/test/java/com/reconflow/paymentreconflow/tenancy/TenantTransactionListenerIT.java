package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.internal.AuditEventRepository;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import com.reconflow.paymentreconflow.support.RlsProbe;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M3.5 through the application: every transaction the app starts tells PostgreSQL its tenant, and
 * a real repository query that asks for the wrong organization's rows gets nothing once the
 * connection runs as a role RLS applies to.
 */
class TenantTransactionListenerIT extends AbstractIntegrationTest {

    @Autowired
    TenantScope tenantScope;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    AuditEventRepository auditEvents;

    @Autowired
    DataSource dataSource;

    @Test
    void everyTransactionCarriesTheBoundTenant() {
        UUID org = data().newOrganization();

        // appJdbc: the application's connection, so the query runs inside tx's transaction.
        String inside = tenantScope.call(
                org,
                () -> tx.execute(
                        s -> appJdbc.queryForObject("select current_setting('app.org_id', true)", String.class)));
        UUID afterwards = tx.execute(s -> appJdbc.queryForObject("select app_current_org()", UUID.class));

        assertThat(inside).isEqualTo(org.toString());
        assertThat(afterwards).isNull(); // no tenant bound, nothing set: SET LOCAL didn't leak
    }

    @Test
    void aQueryForAnotherTenantsRowsIsEmptiedByTheDatabase() {
        new RlsProbe(dataSource); // asserts the app really connects as an unprivileged role
        UUID orgA = data().newOrganization();
        UUID orgB = data().newOrganization();
        UUID entity = Ids.newId();
        jdbc.update(
                "insert into audit_event (id, organization_id, action, entity_type, entity_id) values (?, ?, 'LOGOUT', 'USER', ?)",
                Ids.newId(),
                orgB,
                entity);

        // The row exists (the test's superuser connection sees it)...
        long exists = jdbc.queryForObject("select count(*) from audit_event where entity_id = ?", Long.class, entity);
        // ...and layer 1 is "forgotten": bound to A, but the code passes B's ID. The application
        // connects as reconflow_app (M10.7), so the database hides the row.
        int asApplicationRole = tenantScope.call(
                orgA,
                () -> tx.execute(s -> auditEvents
                        .findByOrganizationIdAndEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(
                                orgB, AuditEntityType.USER, entity)
                        .size()));

        assertThat(exists).isEqualTo(1);
        assertThat(asApplicationRole).isZero();
    }
}
