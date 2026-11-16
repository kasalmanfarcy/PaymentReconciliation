package com.reconflow.paymentreconflow.security.user;

import com.reconflow.paymentreconflow.shared.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * A person who can log in. Global, not owned by an organization: {@link Membership} links a user
 * to each organization with a role.
 *
 * <p>Never bound from a request body or serialized into a response: a body bound to this class
 * would let a client set {@code platformAdmin} (mass assignment). Controllers use request and response
 * records.
 *
 * <p>Since M10.1 passwords, lockout and sessions live in Keycloak. The old columns
 * ({@code password_hash}, {@code failed_login_count}, ...) stay in the table until the contract
 * migration (infra/db/contract) but are no longer mapped.
 */
@Entity
@Table(name = "app_user")
public class AppUser implements Persistable<UUID> {

    @Id
    private UUID id;

    // Stored as entered; looked up with lower(email) so the unique index app_user_email_uk is used.
    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "is_platform_admin", nullable = false)
    private boolean platformAdmin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    /**
     * The identity provider's {@code sub} for this person (M10.1). Null until linked: on creation
     * through the API, or on the first login with a verified email (OidcPrincipalResolver).
     */
    @Column(name = "external_subject")
    private @Nullable String externalSubject;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected AppUser() {} // JPA

    /** A new active user, linked to its identity-provider account (or not yet, when null). */
    public static AppUser create(String email, String displayName, @Nullable String externalSubject) {
        var user = new AppUser();
        user.id = Ids.newId();
        user.email = email.trim();
        user.displayName = displayName.trim();
        user.status = UserStatus.ACTIVE;
        user.externalSubject = externalSubject;
        user.isNew = true;
        return user;
    }

    @Override
    public UUID getId() {
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

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void grantPlatformAdmin() {
        this.platformAdmin = true;
    }

    public @Nullable String getExternalSubject() {
        return externalSubject;
    }

    /** Linked to the identity-provider account that now represents this person. */
    public void linkExternalSubject(String subject) {
        this.externalSubject = subject;
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
    }
}
