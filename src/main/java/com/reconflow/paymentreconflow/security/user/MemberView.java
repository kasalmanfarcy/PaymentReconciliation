package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.shared.context.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * A member of the caller's organization as the API shows it. Never the entity: no platform flag, no
 * identity-provider subject. (Whether a temporary password is pending is Keycloak's to know since
 * M10.1, so {@code mustChangePassword} left the response.)
 */
public record MemberView(
        UUID userId, String email, String displayName, Role role, UserStatus status, Instant createdAt) {}
