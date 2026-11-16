package com.reconflow.paymentreconflow.exceptions.internal.domain;

import com.reconflow.paymentreconflow.shared.id.Ids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

/**
 * An investigation note: evidence, so append-only. {@link Immutable} stops Hibernate from ever
 * issuing an UPDATE; the V6 triggers stop everything else (a native UPDATE, a DELETE, a TRUNCATE).
 * A correction is a new note.
 *
 * <p>The body is kept exactly as written (no trimming): it's evidence. The API returns it as plain
 * text; a UI must escape it on output.
 */
@Entity
@Table(name = "exception_note")
@Immutable
public class ExceptionNote implements Persistable<UUID> {

    public static final int MAX_BODY = 4000;

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "exception_id", nullable = false, updatable = false)
    private UUID exceptionId;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Column(nullable = false, updatable = false)
    private String body;

    // DB default now(): the transaction's start time. Order by (created_at, id); UUIDv7 breaks ties.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew;

    protected ExceptionNote() {} // JPA

    public static ExceptionNote create(UUID organizationId, UUID exceptionId, UUID authorId, String body) {
        var n = new ExceptionNote();
        n.id = Ids.newId();
        n.organizationId = organizationId;
        n.exceptionId = exceptionId;
        n.authorId = authorId;
        n.body = body;
        n.isNew = true;
        return n;
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

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getExceptionId() {
        return exceptionId;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
