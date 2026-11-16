package com.reconflow.paymentreconflow.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One stored row error, read back by {@code GET /imports/{id}/errors} (M5.17). Written with plain
 * JDBC by {@link RowErrorWriter}, in the same transaction as the chunk's records, so this entity is
 * read-only ({@link Immutable}).
 */
@Entity
@Table(name = "import_row_error")
@Immutable
public class ImportRowError {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    /** 1-based data record number: the header, empty lines and line breaks inside quotes don't count. */
    @Column(name = "row_number", nullable = false)
    private int rowNumber;

    /** {@code null} for a whole-row error. */
    @Column(name = "column_name")
    private @Nullable String columnName;

    @Column(name = "error_code", nullable = false)
    private String errorCode;

    @Column(nullable = false)
    private String message;

    @Column(name = "raw_row")
    private @Nullable String rawRow;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected ImportRowError() {} // JPA

    public UUID getId() {
        return id;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public @Nullable String getColumnName() {
        return columnName;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }

    public @Nullable String getRawRow() {
        return rawRow;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
