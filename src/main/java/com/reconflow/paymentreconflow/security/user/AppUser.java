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
import org.springframework.data.domain.Persistable;

/**
 * A person who can log in. Global, not owned by an organization: {@link Membership} links a user
 * to each organization with a role.
 *
 * <p>Never bound from a request body or serialized into a response: a body bound to this class
 * would let a client set {@code platformAdmin} (mass assignment), and a response would leak the
 * password hash. Controllers use request and response records.
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

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "is_platform_admin", nullable = false)
    private boolean platformAdmin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected AppUser() {} // JPA

    /** A new active user. {@code passwordHash} is already encoded ({bcrypt}...). */
    public static AppUser create(String email, String displayName, String passwordHash, boolean mustChangePassword) {
        var user = new AppUser();
        user.id = Ids.newId();
        user.email = email.trim();
        user.displayName = displayName.trim();
        user.passwordHash = passwordHash;
        user.status = UserStatus.ACTIVE;
        user.mustChangePassword = mustChangePassword;
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

    public String getPasswordHash() {
        return passwordHash;
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

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Brute-force lock still running (authentication.plan §4.1). */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void grantPlatformAdmin() {
        this.platformAdmin = true;
    }

    /** Successful login: clear the failure window and record the time. */
    public void recordSuccessfulLogin(Instant now) {
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.lastLoginAt = now;
    }

    /** Rehash on login when the encoder says the stored hash is outdated (cost or algorithm). */
    public void upgradePasswordHash(String newHash) {
        this.passwordHash = newHash;
    }

    /** The user chose a new password: the temporary-password state ends. */
    public void changePassword(String newHash) {
        this.passwordHash = newHash;
        this.mustChangePassword = false;
    }

    /** An admin issued a temporary password; it must be changed at the next login. */
    public void resetPassword(String temporaryHash) {
        this.passwordHash = temporaryHash;
        this.mustChangePassword = true;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
    }

    public void disable() {
        this.status = UserStatus.DISABLED;
    }
}
