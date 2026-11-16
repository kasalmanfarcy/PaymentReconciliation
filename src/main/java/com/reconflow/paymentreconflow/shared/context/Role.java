package com.reconflow.paymentreconflow.shared.context;

/**
 * A member's role in one organization; matches the CHECK on {@code membership.role}.
 *
 * <p>Lives in {@code shared}, not {@code security}, because {@link TenantContext#role()} returns
 * it and every module depends on {@code shared} only. Code never branches on a role: it checks a
 * {@link Permission}, and {@code security.authz.RolePermissions} is the one place roles become
 * permissions. Adding a role is a migration (the CHECK), a constant here and a row there.
 */
public enum Role {
    ORG_ADMIN,
    INVESTIGATOR,
    UPLOADER,
    VIEWER
}
