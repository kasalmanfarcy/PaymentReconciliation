package com.reconflow.paymentreconflow.shared.context;

/**
 * What code checks: {@code @RequiresPermission("EXCEPTION_RESOLVE")}, never a role name. The
 * role-to-permission matrix (authentication.plan §5, api-design.md §9) lives in one class,
 * {@code security.authz.RolePermissions}.
 *
 * <p>{@link #RUN_READ} is not in authentication.plan §5: docs/api-design.md §9 adds it (every
 * role) because runs had no read permission, and the docs win over the plan.
 *
 * <p>{@link #PLATFORM_ORG_MANAGE} is disjoint from the rest: it is granted only on
 * {@code /api/v1/platform/**}, from {@code app_user.is_platform_admin} read from the database, and
 * never satisfies an org-scoped check.
 */
public enum Permission {
    IMPORT_READ,
    TRANSACTION_READ,
    REPORT_READ,
    EXCEPTION_READ,
    RUN_READ,
    IMPORT_CREATE,
    RUN_CREATE,
    EXCEPTION_UPDATE,
    EXCEPTION_RESOLVE,
    EXCEPTION_REOPEN,
    MEMBER_MANAGE,
    AUDIT_READ,
    PLATFORM_ORG_MANAGE
}
