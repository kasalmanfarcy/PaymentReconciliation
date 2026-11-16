package com.reconflow.paymentreconflow.organization;

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
 * A customer business: the tenant boundary. Not tenant-<em>owned</em> (it is the tenant), so its
 * repository is the one that takes no {@code organizationId}.
 *
 * <p>Implements {@link Persistable} because IDs are assigned by the application (UUIDv7): with a
 * non-null ID, Spring Data's {@code save()} would otherwise assume the row exists and call
 * {@code merge}, which runs a SELECT before every INSERT. {@link #isNew()} is true until the
 * entity has been persisted or loaded.
 */
@Entity
@Table(name = "organization")
public class Organization implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrganizationStatus status;

    // Filled by the column default; read it back with refresh() if needed in the same transaction.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected Organization() {} // JPA

    public static Organization create(String name) {
        return createWithId(Ids.newId(), name);
    }

    /**
     * With a caller-chosen ID: the platform flow binds the new org's ID as the tenant before the
     * transaction starts (so its audit rows pass RLS), and the local seeder uses fixed IDs.
     */
    public static Organization createWithId(UUID id, String name) {
        var org = new Organization();
        org.id = id;
        org.name = name;
        org.status = OrganizationStatus.ACTIVE;
        org.isNew = true;
        return org;
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

    public String getName() {
        return name;
    }

    public OrganizationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return status == OrganizationStatus.ACTIVE;
    }

    void rename(String newName) {
        this.name = newName;
    }

    void suspend() {
        this.status = OrganizationStatus.SUSPENDED;
    }
}
