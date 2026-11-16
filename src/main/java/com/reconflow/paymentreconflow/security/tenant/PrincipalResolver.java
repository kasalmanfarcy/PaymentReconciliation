package com.reconflow.paymentreconflow.security.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a validated access token to ReconFlow's own user id (M10.1). The token says who someone is
 * at the identity provider; everything after this (membership, role, permissions) is keyed by our
 * UUID, so no business module knows where users come from.
 */
public interface PrincipalResolver {

    /** Our user id, or empty when the token's subject is unknown here (answered with 401). */
    Optional<UUID> resolveUserId(Jwt jwt);
}
