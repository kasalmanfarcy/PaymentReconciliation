package com.reconflow.paymentreconflow.shared.context;

import java.util.Optional;
import java.util.UUID;

/**
 * The organization the current request or job acts for, with the caller's role and permissions in
 * it. Resolved once per request by {@code security}'s TenantContextFilter (from the
 * {@code X-Organization-Id} header and a fresh membership lookup) or bound by {@link TenantScope}
 * for background work.
 *
 * <p>The only way business code learns the tenant: never from a path, a body or the
 * SecurityContext (ADR 0008). An interface in {@code shared}, implemented in {@code security}, so
 * {@code audit} can read it without depending on {@code security} (which itself writes audit
 * events), and so M10's Keycloak move replaces the implementation, not the callers.
 */
public interface TenantContext {

    /** The bound organization. @throws NoTenantException if none is bound. */
    UUID organizationId();

    Optional<UUID> organizationIdIfPresent();

    /** The caller's role in the bound organization; null for system work and platform routes. */
    Role role();

    boolean has(Permission permission);
}
