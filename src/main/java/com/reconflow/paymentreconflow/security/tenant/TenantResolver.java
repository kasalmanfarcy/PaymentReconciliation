package com.reconflow.paymentreconflow.security.tenant;

import com.reconflow.paymentreconflow.security.authz.RolePermissions;
import com.reconflow.paymentreconflow.security.tenant.ScopedTenantHolder.Bound;
import com.reconflow.paymentreconflow.security.tenant.TenantLookup.MembershipRow;
import com.reconflow.paymentreconflow.security.tenant.TenantLookup.UserMemberships;
import com.reconflow.paymentreconflow.security.user.UserStatus;
import com.reconflow.paymentreconflow.shared.context.Permission;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;

/**
 * The resolution rules of authentication.plan §4.2 and docs/api-design.md §2, as pure functions so
 * every rule has a unit test. Where the two disagree, the docs win: a malformed header is
 * {@code 400 validation-failed}.
 */
final class TenantResolver {

    /** Either a binding or the error to answer with. */
    sealed interface Resolution {
        record Resolved(Bound bound) implements Resolution {}

        record Rejected(
                HttpStatus status,
                String code,
                String detail,
                @Nullable Map<String, String> fieldError) implements Resolution {}
    }

    static final String HEADER = "X-Organization-Id";

    private TenantResolver() {}

    /**
     * Org-scoped routes. A user who isn't a member, a suspended organization, and a user with no
     * membership at all (a platform admin) all get 404: the response must not confirm that the
     * organization exists.
     */
    static Resolution tenant(UUID userId, Optional<UserMemberships> found, @Nullable String header) {
        Optional<Resolution> inactive = rejectInactive(found);
        if (inactive.isPresent()) {
            return inactive.get();
        }
        List<MembershipRow> usable = found.get().memberships().stream()
                .filter(MembershipRow::organizationActive)
                .toList();
        MembershipRow chosen;
        if (header != null && !header.isBlank()) {
            UUID requested;
            try {
                requested = UUID.fromString(header.trim());
            } catch (IllegalArgumentException e) {
                return new Resolution.Rejected(
                        HttpStatus.BAD_REQUEST,
                        ProblemCodes.VALIDATION_FAILED,
                        "The " + HEADER + " header must be a UUID.",
                        Map.of("field", HEADER, "code", "invalid-uuid", "message", "Not a UUID."));
            }
            chosen = usable.stream()
                    .filter(m -> m.organizationId().equals(requested))
                    .findFirst()
                    .orElse(null);
            if (chosen == null) {
                return organizationNotFound();
            }
        } else if (usable.size() == 1) {
            chosen = usable.getFirst();
        } else if (usable.isEmpty()) {
            return organizationNotFound();
        } else {
            return new Resolution.Rejected(
                    HttpStatus.BAD_REQUEST,
                    ProblemCodes.ORGANIZATION_REQUIRED,
                    "You belong to several organizations: choose one with the " + HEADER + " header.",
                    null);
        }
        return new Resolution.Resolved(
                new Bound(userId, chosen.organizationId(), chosen.role(), RolePermissions.of(chosen.role())));
    }

    /**
     * {@code /api/v1/platform/**}: no organization and no header. PLATFORM_ORG_MANAGE comes only
     * from the database flag, read now, never from the token; a non-admin is authenticated with no
     * authorities, so the URL rule answers 403.
     */
    static Resolution platform(UUID userId, Optional<UserMemberships> found) {
        Optional<Resolution> inactive = rejectInactive(found);
        if (inactive.isPresent()) {
            return inactive.get();
        }
        Set<Permission> permissions = found.get().platformAdmin() ? Set.of(Permission.PLATFORM_ORG_MANAGE) : Set.of();
        return new Resolution.Resolved(new Bound(userId, null, null, permissions));
    }

    /**
     * Authenticated routes outside {@code /api/v1/} (none in the API since M10.1 moved password
     * changes to Keycloak): an active user, nothing more.
     */
    static Resolution identity(UUID userId, Optional<UserMemberships> found) {
        return rejectInactive(found).orElseGet(() -> new Resolution.Resolved(new Bound(userId, null, null, Set.of())));
    }

    /** A deleted, disabled or administratively locked user is out on the very next request. */
    private static Optional<Resolution> rejectInactive(Optional<UserMemberships> found) {
        if (found.isEmpty() || found.get().status() != UserStatus.ACTIVE) {
            return Optional.of(new Resolution.Rejected(
                    HttpStatus.UNAUTHORIZED, ProblemCodes.UNAUTHENTICATED, "Authentication is required.", null));
        }
        return Optional.empty();
    }

    private static Resolution organizationNotFound() {
        return new Resolution.Rejected(
                HttpStatus.NOT_FOUND, ProblemCodes.ORGANIZATION_NOT_FOUND, "The organization was not found.", null);
    }
}
