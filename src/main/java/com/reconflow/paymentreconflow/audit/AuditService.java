package com.reconflow.paymentreconflow.audit;

import com.reconflow.paymentreconflow.audit.internal.AuditEvent;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.web.RequestIdFilter;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single way to write an audit event, from any module.
 *
 * <p>{@link Propagation#MANDATORY}: the event is written in the <em>caller's</em> transaction, so
 * it commits or rolls back together with the change it describes, and the log can never disagree
 * with the data. With the default REQUIRED, a caller who forgot {@code @Transactional} would
 * silently get the audit row in a transaction of its own; MANDATORY turns that into an
 * IllegalTransactionStateException in the first test that runs the path.
 *
 * <p>The flip side: a failure that must be recorded (a failed login) must <em>commit</em>. A
 * caller that audits and then throws a RuntimeException rolls its own audit row back.
 *
 * <p>Exceptions are never swallowed here: if the audit insert fails, the business change fails
 * too. An unaudited change is worse than a failed one.
 */
@Service
public class AuditService {

    static final int MAX_PAYLOAD_BYTES = 8 * 1024;

    private final EntityManager em;
    private final TenantContext tenant;
    private final CurrentUser currentUser;
    private final JsonMapper json;

    AuditService(EntityManager em, TenantContext tenant, CurrentUser currentUser, JsonMapper json) {
        this.em = em;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        UUID organizationId = entry.organizationOverride() != null
                ? entry.organizationOverride()
                : tenant.organizationIdIfPresent().orElse(null); // NULL: a platform event
        UUID actorId = entry.actorOverride() != null
                ? entry.actorOverride()
                : currentUser.idIfPresent().orElse(null); // NULL: a system action
        enforceSize(entry);

        // persist, not a JdbcTemplate insert: it joins the persistence context, so the insert is
        // flushed with the business changes in order, in the same transaction.
        em.persist(AuditEvent.of(
                organizationId,
                actorId,
                entry.action(),
                entry.entityType(),
                entry.entityId(),
                entry.payload(),
                MDC.get(RequestIdFilter.MDC_KEY)));
    }

    /**
     * An audit log that accepts megabytes per event is a storage DoS and an unreadable timeline.
     * Oversized payloads are a programming error: throw, roll the business change back, fail the
     * test loudly.
     */
    private void enforceSize(AuditEntry entry) {
        int bytes = json.writeValueAsBytes(entry.payload()).length;
        if (bytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Audit payload for " + entry.action() + " is " + bytes
                    + " bytes; the limit is " + MAX_PAYLOAD_BYTES);
        }
    }
}
