package com.reconflow.paymentreconflow.audit;

import static com.reconflow.paymentreconflow.audit.AuditAction.LOGOUT;
import static com.reconflow.paymentreconflow.audit.AuditAction.ORGANIZATION_CREATED;
import static com.reconflow.paymentreconflow.audit.AuditEntityType.ORGANIZATION;
import static com.reconflow.paymentreconflow.audit.AuditEntityType.USER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reconflow.paymentreconflow.organization.Organization;
import com.reconflow.paymentreconflow.organization.OrganizationRepository;
import com.reconflow.paymentreconflow.organization.OrganizationService;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.web.RequestIdFilter;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M4.4: the audit row commits or rolls back with the business change.
 *
 * <p>Transactions come from a {@link TransactionTemplate}, never {@code @Transactional} on the test:
 * a test-managed transaction always rolls back, which would make the "committed" case meaningless.
 * Counts use plain JDBC as the container's superuser, so row-level security can't hide rows from
 * the assertions.
 */
class AuditServiceIT extends AbstractIntegrationTest {

    @Autowired
    AuditService audit;

    @Autowired
    TenantScope tenantScope;

    @Autowired
    OrganizationService orgService;

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    PlatformTransactionManager txManager;

    private Organization newOrganization() {
        return tx.execute(s -> organizations.save(Organization.create("Audit " + Ids.newId())));
    }

    private int countAuditRows(UUID entityId) {
        return jdbc.queryForObject("select count(*) from audit_event where entity_id = ?", Integer.class, entityId);
    }

    @Test
    void rolledBackTransactionLeavesNoAuditRow() {
        var org = newOrganization();
        var entityId = Ids.newId();

        assertThatThrownBy(() -> tenantScope.run(
                        org.getId(),
                        () -> tx.executeWithoutResult(status -> {
                            orgService.rename(org.getId(), "Renamed"); // a real business change
                            audit.record(AuditEntry.of(ORGANIZATION_CREATED, ORGANIZATION, entityId));
                            throw new IllegalStateException("boom"); // forces the rollback
                        })))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countAuditRows(entityId)).isZero();
        assertThat(organizations.findById(org.getId()).orElseThrow().getName()).isNotEqualTo("Renamed");
    }

    @Test
    void committedTransactionWritesExactlyOneRowWithItsContext() {
        var org = newOrganization();
        var entityId = Ids.newId();
        var actor = jdbcUser();

        MDC.put(RequestIdFilter.MDC_KEY, "audit-it-request");
        try {
            tenantScope.run(
                    org.getId(),
                    () -> tx.executeWithoutResult(status -> audit.record(
                            AuditEntry.of(LOGOUT, USER, entityId).actorId(actor).with("ip", "203.0.113.7"))));
        } finally {
            MDC.remove(RequestIdFilter.MDC_KEY);
        }

        Map<String, Object> row = jdbc.queryForMap(
                "select organization_id, actor_id, action, entity_type, request_id, payload->>'ip' as ip"
                        + " from audit_event where entity_id = ?",
                entityId);
        assertThat(row)
                .containsEntry("organization_id", org.getId()) // from TenantScope
                .containsEntry("actor_id", actor)
                .containsEntry("action", "LOGOUT")
                .containsEntry("entity_type", "USER")
                .containsEntry("request_id", "audit-it-request") // from the MDC
                .containsEntry("ip", "203.0.113.7");
        assertThat(countAuditRows(entityId)).isEqualTo(1);
    }

    @Test
    void workerContextRecordsTheJobsOrganizationAndNoActor() {
        var org = newOrganization();
        var entityId = Ids.newId();

        tenantScope.run(
                org.getId(),
                () -> tx.executeWithoutResult(status -> audit.record(AuditEntry.of(LOGOUT, USER, entityId))));

        Map<String, Object> row =
                jdbc.queryForMap("select organization_id, actor_id from audit_event where entity_id = ?", entityId);
        assertThat(row.get("organization_id")).isEqualTo(org.getId());
        assertThat(row.get("actor_id")).isNull();
    }

    @Test
    void recordOutsideATransactionIsRejected() {
        // Goes through the Spring proxy, so MANDATORY is really checked (self-invocation would skip it).
        assertThatThrownBy(() -> audit.record(AuditEntry.of(LOGOUT, USER, Ids.newId())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void requiresNewWouldHaveKeptTheEventOfARolledBackChange() {
        // Documents the bug MANDATORY prevents: an audit write in its own transaction survives the
        // rollback of the change it describes, so the log claims something that never happened.
        var entityId = Ids.newId();
        var requiresNew = new TransactionTemplate(txManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    requiresNew.executeWithoutResult(inner -> audit.record(AuditEntry.of(LOGOUT, USER, entityId)));
                    throw new IllegalStateException("business change fails");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countAuditRows(entityId)).isEqualTo(1);
    }

    private UUID jdbcUser() {
        UUID id = Ids.newId();
        jdbc.update(
                "insert into app_user (id, email, display_name, password_hash) values (?, ?, 'Auditor', '{noop}x')",
                id,
                "auditor-" + id + "@example.test");
        return id;
    }
}
