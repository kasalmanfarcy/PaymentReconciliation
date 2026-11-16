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

        String inside = tenantScope.call(
                org,
                () -> tx.execute(s -> jdbc.queryForObject("select current_setting('app.org_id', true)", String.class)));
        UUID afterwards = tx.execute(s -> jdbc.queryForObject("select app_current_org()", UUID.class));

        assertThat(inside).isEqualTo(org.toString());
        assertThat(afterwards).isNull(); // no tenant bound, nothing set: SET LOCAL didn't leak
    }

    @Test
    void aQueryForAnotherTenantsRowsIsEmptiedByTheDatabase() {
        new RlsProbe(dataSource, jdbc); // ensures the role exists
        UUID orgA = data().newOrganization();
        UUID orgB = data().newOrganization();
        UUID entity = Ids.newId();
        jdbc.update(
                "insert into audit_event (id, organization_id, action, entity_type, entity_id) values (?, ?, 'LOGOUT', 'USER', ?)",
                Ids.newId(),
                orgB,
                entity);

        // Layer 1 "forgotten": bound to A, but the code passes B's ID. As the superuser (today's
        // connection) the row comes back, which proves the query really asks for B's data...
        int asSuperuser = tenantScope.call(
                orgA,
                () -> tx.execute(s -> auditEvents
                        .findByOrganizationIdAndEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(
                                orgB, AuditEntityType.USER, entity)
                        .size()));
        // ...and as a role RLS applies to (M10.7's application role), the database hides it.
        int asApplicationRole = tenantScope.call(
                orgA,
                () -> tx.execute(s -> {
                    jdbc.execute("SET LOCAL ROLE " + RlsProbe.ROLE);
                    return auditEvents
                            .findByOrganizationIdAndEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(
                                    orgB, AuditEntityType.USER, entity)
                            .size();
                }));

        assertThat(asSuperuser).isEqualTo(1);
        assertThat(asApplicationRole).isZero();
    }
}
