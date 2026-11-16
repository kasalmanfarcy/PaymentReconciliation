/**
 * Security: users, membership, login, tokens, the tenant-context filter and the role to
 * permission mapping (layout: plans/authentication.plan §6). Implements the TenantContext,
 * CurrentUser, TenantScope and Permission interfaces declared in {@code shared}.
 * Owns {@code app_user}, {@code membership} and {@code refresh_token}.
 *
 * <p>Exposes almost nothing: other modules may only use the user directory (membership and
 * display-name lookups). Login, tokens and the filter are internal.
 */
package com.reconflow.paymentreconflow.security;
