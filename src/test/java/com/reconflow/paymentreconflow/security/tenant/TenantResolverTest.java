package com.reconflow.paymentreconflow.security.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.security.tenant.TenantLookup.MembershipRow;
import com.reconflow.paymentreconflow.security.tenant.TenantLookup.UserMemberships;
import com.reconflow.paymentreconflow.security.tenant.TenantResolver.Resolution;
import com.reconflow.paymentreconflow.security.user.UserStatus;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.context.Role;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every rule of authentication.plan §4.2 / api-design.md §2, without a database. */
class TenantResolverTest {

    private final UUID user = UUID.randomUUID();
    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    private static Optional<UserMemberships> active(MembershipRow... rows) {
        return Optional.of(new UserMemberships(UserStatus.ACTIVE, false, List.of(rows)));
    }

    private MembershipRow member(UUID org, Role role) {
        return new MembershipRow(org, role, true);
    }

    private static ScopedTenantHolder.Bound resolved(Resolution r) {
        assertThat(r).isInstanceOf(Resolution.Resolved.class);
        return ((Resolution.Resolved) r).bound();
    }

    private static void rejected(Resolution r, int status, String code) {
        assertThat(r).isInstanceOfSatisfying(Resolution.Rejected.class, rej -> {
            assertThat(rej.status().value()).isEqualTo(status);
            assertThat(rej.code()).isEqualTo(code);
        });
    }

    @Test
    void headerNamingAMembershipSelectsItWithTheRolesPermissions() {
        var bound = resolved(TenantResolver.tenant(
                user, active(member(orgA, Role.VIEWER), member(orgB, Role.ORG_ADMIN)), orgB.toString()));

        assertThat(bound.organizationId()).isEqualTo(orgB);
        assertThat(bound.userId()).isEqualTo(user);
        assertThat(bound.role()).isEqualTo(Role.ORG_ADMIN);
        assertThat(bound.permissions()).contains(Permission.MEMBER_MANAGE);
    }

    @Test
    void noHeaderAndOneMembershipUsesIt() {
        assertThat(resolved(TenantResolver.tenant(user, active(member(orgA, Role.VIEWER)), null))
                        .organizationId())
                .isEqualTo(orgA);
    }

    @Test
    void noHeaderAndSeveralMembershipsIs400OrganizationRequired() {
        rejected(
                TenantResolver.tenant(user, active(member(orgA, Role.VIEWER), member(orgB, Role.VIEWER)), null),
                400,
                "organization-required");
    }

    @Test
    void aForeignOrganizationIs404() {
        rejected(
                TenantResolver.tenant(user, active(member(orgA, Role.ORG_ADMIN)), orgB.toString()),
                404,
                "organization-not-found");
    }

    @Test
    void aMalformedHeaderIs400ValidationFailed() {
        rejected(
                TenantResolver.tenant(user, active(member(orgA, Role.VIEWER)), "not-a-uuid"), 400, "validation-failed");
    }

    @Test
    void aSuspendedOrganizationIs404EvenForItsMembers() {
        var suspended = new MembershipRow(orgA, Role.ORG_ADMIN, false);

        rejected(TenantResolver.tenant(user, active(suspended), orgA.toString()), 404, "organization-not-found");
        rejected(TenantResolver.tenant(user, active(suspended), null), 404, "organization-not-found");
        // A suspended org doesn't count towards "several": the other one is used.
        assertThat(resolved(TenantResolver.tenant(user, active(suspended, member(orgB, Role.VIEWER)), null))
                        .organizationId())
                .isEqualTo(orgB);
    }

    @Test
    void aDisabledLockedOrDeletedUserIs401() {
        for (UserStatus status : List.of(UserStatus.DISABLED, UserStatus.LOCKED)) {
            var found = Optional.of(new UserMemberships(status, false, List.of(member(orgA, Role.ORG_ADMIN))));
            rejected(TenantResolver.tenant(user, found, orgA.toString()), 401, "unauthenticated");
            rejected(TenantResolver.platform(user, found), 401, "unauthenticated");
            rejected(TenantResolver.identity(user, found), 401, "unauthenticated");
        }
        rejected(TenantResolver.tenant(user, Optional.empty(), null), 401, "unauthenticated");
    }

    @Test
    void aPlatformAdminWithoutMembershipsGets404OnOrgRoutes() {
        var admin = Optional.of(new UserMemberships(UserStatus.ACTIVE, true, List.of()));

        rejected(TenantResolver.tenant(user, admin, null), 404, "organization-not-found");
        rejected(TenantResolver.tenant(user, admin, orgA.toString()), 404, "organization-not-found");
    }

    @Test
    void platformRoutesGrantPlatformOrgManageOnlyFromTheDatabaseFlag() {
        var admin = Optional.of(new UserMemberships(UserStatus.ACTIVE, true, List.of()));
        var orgAdmin = active(member(orgA, Role.ORG_ADMIN));

        var adminBound = resolved(TenantResolver.platform(user, admin));
        assertThat(adminBound.permissions()).containsExactly(Permission.PLATFORM_ORG_MANAGE);
        assertThat(adminBound.organizationId()).isNull();
        // An org admin is authenticated on platform routes but with no authority: the URL rule gives 403.
        assertThat(resolved(TenantResolver.platform(user, orgAdmin)).permissions())
                .isEmpty();
    }

    @Test
    void identityRoutesNeedOnlyAnActiveUser() {
        var bound =
                resolved(TenantResolver.identity(user, active(member(orgA, Role.VIEWER), member(orgB, Role.VIEWER))));

        assertThat(bound.userId()).isEqualTo(user);
        assertThat(bound.organizationId()).isNull();
    }
}
