/**
 * Security: users, membership, login, tokens, the tenant-context filter and the role to
 * permission mapping (layout: plans/authentication.plan §6). Implements the TenantContext,
 * CurrentUser, TenantScope and Permission interfaces declared in {@code shared}.
 * Owns {@code app_user}, {@code membership} and {@code refresh_token}.
 *
 * <p>Exposes almost nothing: other modules may only use the user directory (membership and
 * display-name lookups). Login, tokens and the filter are internal.
 */
// Allowed dependencies: docs/architecture.md §4. A dependency not listed here fails ModularityTest,
// even if it would not create a cycle.
@ApplicationModule(displayName = "Security", allowedDependencies = {"organization", "audit", "shared"})
package com.reconflow.paymentreconflow.security;

import org.springframework.modulith.ApplicationModule;
