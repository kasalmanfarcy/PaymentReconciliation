package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.shared.context.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * A member of the caller's organization as the API shows it. Never the entity: no password hash,
 * no lockout counters, no platform flag.
 */
public record MemberView(
        UUID userId,
        String email,
        String displayName,
        Role role,
        UserStatus status,
        boolean mustChangePassword,
        Instant createdAt) {}
