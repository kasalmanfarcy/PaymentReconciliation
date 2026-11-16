package com.reconflow.paymentreconflow.security.authz;

import static com.reconflow.paymentreconflow.shared.context.Permission.AUDIT_READ;
import static com.reconflow.paymentreconflow.shared.context.Permission.EXCEPTION_READ;
import static com.reconflow.paymentreconflow.shared.context.Permission.EXCEPTION_REOPEN;
import static com.reconflow.paymentreconflow.shared.context.Permission.EXCEPTION_RESOLVE;
import static com.reconflow.paymentreconflow.shared.context.Permission.EXCEPTION_UPDATE;
import static com.reconflow.paymentreconflow.shared.context.Permission.IMPORT_CREATE;
import static com.reconflow.paymentreconflow.shared.context.Permission.IMPORT_READ;
import static com.reconflow.paymentreconflow.shared.context.Permission.MEMBER_MANAGE;
import static com.reconflow.paymentreconflow.shared.context.Permission.REPORT_READ;
import static com.reconflow.paymentreconflow.shared.context.Permission.RUN_CREATE;
import static com.reconflow.paymentreconflow.shared.context.Permission.RUN_READ;
import static com.reconflow.paymentreconflow.shared.context.Permission.TRANSACTION_READ;

import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The only place roles turn into permissions (docs/api-design.md §9, authentication.plan §5).
 * Giving uploaders a new right is one line here, not a search through every controller for
 * {@code hasRole}.
 *
 * <p>{@link Permission#PLATFORM_ORG_MANAGE} is in no role: it comes only from
 * {@code app_user.is_platform_admin}, on platform routes, so it can never satisfy an org-scoped
 * check and an org role can never reach a platform route.
 */
public final class RolePermissions {

    private static final Set<Permission> READ =
            EnumSet.of(IMPORT_READ, TRANSACTION_READ, REPORT_READ, EXCEPTION_READ, RUN_READ);

    private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

    static {
        MATRIX.put(Role.VIEWER, READ);
        MATRIX.put(Role.UPLOADER, union(READ, IMPORT_CREATE, RUN_CREATE));
        MATRIX.put(Role.INVESTIGATOR, union(READ, RUN_CREATE, EXCEPTION_UPDATE, EXCEPTION_RESOLVE));
        MATRIX.put(
                Role.ORG_ADMIN,
                union(
                        READ,
                        IMPORT_CREATE,
                        RUN_CREATE,
                        EXCEPTION_UPDATE,
                        EXCEPTION_RESOLVE,
                        EXCEPTION_REOPEN,
                        MEMBER_MANAGE,
                        AUDIT_READ));
    }

    private RolePermissions() {}

    public static Set<Permission> of(Role role) {
        return MATRIX.get(role);
    }

    private static Set<Permission> union(Set<Permission> base, Permission... more) {
        EnumSet<Permission> all = EnumSet.copyOf(base);
        Collections.addAll(all, more);
        return Collections.unmodifiableSet(all);
    }
}
