package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.shared.context.Role;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * A user's role in one organization. IDs only, no {@code @ManyToOne}: the organization module owns
 * {@code Organization}, and with open-in-view off a lazy association would only be a way to hit
 * LazyInitializationException.
 */
@Entity
@Table(name = "membership")
public class Membership implements Persistable<MembershipId> {

    @EmbeddedId
    private MembershipId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // NULL only for seeded and bootstrapped memberships.
    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew;

    protected Membership() {} // JPA

    public static Membership create(UUID userId, UUID organizationId, Role role, UUID createdBy) {
        var m = new Membership();
        m.id = new MembershipId(userId, organizationId);
        m.role = role;
        m.createdBy = createdBy;
        m.isNew = true;
        return m;
    }

    @Override
    public MembershipId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        isNew = false;
    }

    public UUID getUserId() {
        return id.userId();
    }

    public UUID getOrganizationId() {
        return id.organizationId();
    }

    public Role getRole() {
        return role;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void changeRole(Role newRole) {
        this.role = newRole;
    }
}
