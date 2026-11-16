package com.reconflow.paymentreconflow.security.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Users are global, not tenant-owned, so this repository is on the allow-list of the
 * "tenant repositories take organizationId" rule.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /**
     * Explicit lower() on both sides so the unique index on lower(email) is usable. A derived
     * findByEmailIgnoreCase may generate upper(...) = upper(...), which no index matches.
     */
    @Query("select u from AppUser u where lower(u.email) = lower(:email)")
    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByPlatformAdminTrue();
}
