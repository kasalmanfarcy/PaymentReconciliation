package com.reconflow.paymentreconflow.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.audit.internal.AuditEvent;
import com.reconflow.paymentreconflow.audit.internal.AuditEventRepository;
import com.reconflow.paymentreconflow.organization.Organization;
import com.reconflow.paymentreconflow.organization.OrganizationRepository;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** M4.1: the entity maps onto audit_event, including the JSONB payload, under ddl-auto=validate. */
class AuditEventMappingIT extends AbstractIntegrationTest {

    @Autowired
    EntityManager em;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    AuditEventRepository events;

    @Test
    void nestedPayloadWithAMoneyStringRoundTrips() {
        UUID org = tx.execute(s -> organizations.save(Organization.create("Audit mapping")))
                .getId();
        UUID entityId = Ids.newId();
        Map<String, Object> payload = Map.of(
                "amount",
                "1000.00",
                "currency",
                "AED",
                "nested",
                Map.of("from", "OPEN", "to", "RESOLVED", "tags", List.of("a", "b")),
                "version",
                3);

        // The application's connection is subject to RLS (M10.7): bind the event's organization.
        inTenant(
                org,
                () -> tx.executeWithoutResult(s -> em.persist(AuditEvent.of(
                        org,
                        null,
                        AuditAction.EXCEPTION_RESOLVED,
                        AuditEntityType.EXCEPTION_CASE,
                        entityId,
                        payload,
                        "req-1"))));

        // Tenant data is read inside a transaction, where the tenant reaches the database.
        List<AuditEvent> found = asTenant(
                org,
                () -> tx.execute(s -> events.findByOrganizationIdAndEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(
                        org, AuditEntityType.EXCEPTION_CASE, entityId)));
        assertThat(found).singleElement().satisfies(e -> {
            assertThat(e.getPayload()).isEqualTo(payload);
            assertThat(e.getPayload().get("amount")).isEqualTo("1000.00"); // a string, not 1E+3
            assertThat(e.getAction()).isEqualTo(AuditAction.EXCEPTION_RESOLVED);
            assertThat(e.getRequestId()).isEqualTo("req-1");
            assertThat(e.getCreatedAt()).isNotNull();
        });
        assertThat(jdbc.queryForObject(
                        "select payload->'nested'->>'to' from audit_event where entity_id = ?", String.class, entityId))
                .isEqualTo("RESOLVED");
    }
}
