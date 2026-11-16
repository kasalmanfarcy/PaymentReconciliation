package com.reconflow.paymentreconflow.ingestion.domain;

import com.reconflow.paymentreconflow.ledger.SourceType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * One uploaded file and its processing state: the unit of work a user sees (M5.1).
 *
 * <p>No public setters: state changes go through named methods, so there is one place to enforce
 * each rule. The row counters and the {@code last_committed_row} checkpoint are deliberately
 * <em>not</em> changed through this entity during processing: each chunk advances them with one SQL
 * {@code UPDATE} in the chunk's own transaction (ImportBatchRepository#recordChunk), so they can
 * never be overwritten by a stale copy of the entity.
 *
 * <p>There is no {@code @Version}: transitions take a row lock ({@code SELECT ... FOR UPDATE})
 * instead. Batch transitions are rare and short, so a lock is simpler and just as correct.
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false)
    private SourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ImportBatchStatus status;

    /** User input: for display only, never part of a path or storage key. */
    @Column(name = "file_name", nullable = false, updatable = false)
    private String fileName;

    @Column(name = "file_size_bytes", nullable = false, updatable = false)
    private long fileSizeBytes;

    // CHAR(64): without the JDBC type Hibernate expects varchar and ddl-auto=validate fails on bpchar.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    private String contentHash;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "valid_rows", nullable = false)
    private int validRows;

    @Column(name = "error_rows", nullable = false)
    private int errorRows;

    @Column(name = "last_committed_row", nullable = false)
    private int lastCommittedRow;

    @Column(name = "failure_reason")
    private @Nullable String failureReason;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private UUID uploadedBy;

    @Column(name = "started_at")
    private @Nullable Instant startedAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Maintained by the application (database-design.md §1); SQL updates set it themselves.
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean isNew;

    protected ImportBatch() {} // JPA

    /** A batch just accepted at upload: {@code RECEIVED}, every counter at zero. */
    public static ImportBatch received(
            UUID id,
            UUID organizationId,
            SourceType sourceType,
            String fileName,
            long fileSizeBytes,
            String contentHash,
            String storageKey,
            UUID uploadedBy,
            Instant now) {
        var b = new ImportBatch();
        b.id = id;
        b.organizationId = organizationId;
        b.sourceType = sourceType;
        b.status = ImportBatchStatus.RECEIVED;
        b.fileName = fileName;
        b.fileSizeBytes = fileSizeBytes;
        b.contentHash = contentHash;
        b.storageKey = storageKey;
        b.uploadedBy = uploadedBy;
        // Set here rather than by the column default, so the 202 response can show it without a
        // re-read.
        b.createdAt = now;
        b.updatedAt = now;
        b.isNew = true;
        return b;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** Assigned UUIDv7 ids: tells Spring Data to persist rather than merge (no SELECT first). */
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

    public SourceType getSourceType() {
        return sourceType;
    }

    public ImportBatchStatus getStatus() {
        return status;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getValidRows() {
        return validRows;
    }

    public int getErrorRows() {
        return errorRows;
    }

    public int getLastCommittedRow() {
        return lastCommittedRow;
    }

    public @Nullable String getFailureReason() {
        return failureReason;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public @Nullable Instant getStartedAt() {
        return startedAt;
    }

    public @Nullable Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
