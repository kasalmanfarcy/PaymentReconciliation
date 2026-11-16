package com.reconflow.paymentreconflow.audit;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What to record, built at the call site so it reads like a sentence:
 *
 * <pre>{@code
 * audit.record(AuditEntry.of(EXCEPTION_RESOLVED, EXCEPTION_CASE, c.getId())
 *     .with("resolutionCode", code)
 *     .with("version", c.getVersion()));
 * }</pre>
 *
 * Immutable: every {@code with...} returns a copy. Organization and actor normally come from the
 * bound TenantContext and CurrentUser; the overrides are for events with no bound user (a login,
 * where the user is known only from the credentials) or a different actor.
 *
 * <p>Payload rules: what changed, why, and the entity's version after the change. Never passwords
 * or tokens (raw or hashed), never full CSV rows or record lists.
 */
public record AuditEntry(
        AuditAction action,
        AuditEntityType entityType,
        @Nullable UUID entityId,
        Map<String, @Nullable Object> payload,
        @Nullable UUID organizationOverride,
        @Nullable UUID actorOverride) {

    public AuditEntry {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    public static AuditEntry of(AuditAction action, AuditEntityType entityType, @Nullable UUID entityId) {
        return new AuditEntry(action, entityType, entityId, Map.of(), null, null);
    }

    /**
     * Adds a payload value. Money ({@link BigDecimal}) is stored as a plain-notation string, the
     * same rule as the API (api-design.md §1): "1000.00", never 1E+3 or a binary double.
     */
    public AuditEntry with(String key, @Nullable Object value) {
        var copy = new LinkedHashMap<String, @Nullable Object>(payload);
        copy.put(key, value instanceof BigDecimal money ? money.toPlainString() : value);
        return new AuditEntry(action, entityType, entityId, copy, organizationOverride, actorOverride);
    }

    /**
     * Records the event for this organization instead of the bound one. RLS only accepts an
     * audit row for the organization bound to the transaction, so in practice this must match it;
     * to write for another organization, bind it first with TenantScope.
     */
    public AuditEntry organizationId(UUID organizationId) {
        return new AuditEntry(action, entityType, entityId, payload, organizationId, actorOverride);
    }

    /** The responsible user when none is bound, e.g. the user who just logged in. */
    public AuditEntry actorId(@Nullable UUID actorId) {
        return new AuditEntry(action, entityType, entityId, payload, organizationOverride, actorId);
    }
}
