package com.reconflow.paymentreconflow.security.token;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Read-only; owned by a user, not an organization, so it is on the organizationId allow-list. */
public interface RefreshTokenRepository extends Repository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);

    List<RefreshToken> findByUserId(UUID userId);
}
