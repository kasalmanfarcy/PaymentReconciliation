package com.reconflow.paymentreconflow.security.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * {@link Repository}, not JpaRepository: no inherited findAll() or deleteById() without an
 * organization. Membership spans organizations by nature (a user's memberships at login), so it is
 * on the allow-list of the organizationId rule, but every per-organization method still takes one.
 */
public interface MembershipRepository extends Repository<Membership, MembershipId> {

    /** All of one user's memberships, in one query (login response). */
    List<Membership> findByIdUserId(UUID userId);

    Optional<Membership> findById(MembershipId id);

    List<Membership> findByIdOrganizationId(UUID organizationId);

    Membership save(Membership membership);
}
