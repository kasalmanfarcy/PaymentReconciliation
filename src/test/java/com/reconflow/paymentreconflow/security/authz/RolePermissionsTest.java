package com.reconflow.paymentreconflow.security.authz;

import static com.reconflow.paymentreconflow.shared.context.Permission.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import org.junit.jupiter.api.Test;

/** The matrix of docs/api-design.md §9, cell by cell. */
class RolePermissionsTest {

    @Test
    void viewerCanOnlyRead() {
        assertThat(RolePermissions.of(Role.VIEWER))
                .containsExactlyInAnyOrder(IMPORT_READ, TRANSACTION_READ, REPORT_READ, EXCEPTION_READ, RUN_READ);
    }

    @Test
    void uploaderAddsImportsAndRuns() {
        assertThat(RolePermissions.of(Role.UPLOADER))
                .containsExactlyInAnyOrder(
                        IMPORT_READ,
                        TRANSACTION_READ,
                        REPORT_READ,
                        EXCEPTION_READ,
                        RUN_READ,
                        IMPORT_CREATE,
                        RUN_CREATE);
    }

    @Test
    void investigatorWorksExceptionsButCannotUploadOrReopen() {
        assertThat(RolePermissions.of(Role.INVESTIGATOR))
                .containsExactlyInAnyOrder(
                        IMPORT_READ,
                        TRANSACTION_READ,
                        REPORT_READ,
                        EXCEPTION_READ,
                        RUN_READ,
                        RUN_CREATE,
                        EXCEPTION_UPDATE,
                        EXCEPTION_RESOLVE);
    }

    @Test
    void orgAdminHasEveryOrgPermission() {
        assertThat(RolePermissions.of(Role.ORG_ADMIN))
                .containsExactlyInAnyOrder(
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
                        AUDIT_READ);
    }

    @Test
    void noRoleEverGrantsPlatformOrgManage() {
        for (Role role : Role.values()) {
            assertThat(RolePermissions.of(role)).doesNotContain(Permission.PLATFORM_ORG_MANAGE);
        }
    }
}
