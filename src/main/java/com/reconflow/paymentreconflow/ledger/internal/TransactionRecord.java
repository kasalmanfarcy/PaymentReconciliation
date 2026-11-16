package com.reconflow.paymentreconflow.ledger.internal;

import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.ledger.TransactionStatus;
import com.reconflow.paymentreconflow.ledger.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Read-side mapping of {@code transaction_record} (M5.3). Writes never go through it: the writer
 * uses JDBC batches. {@link Immutable} documents and enforces that imported financial records are
 * never edited; the one flag that changes after insert ({@code is_duplicate_in_source}) is set by a
 * set-based SQL update. Validated against the schema at startup ({@code ddl-auto=validate}).
 */
@Entity
@Table(name = "transaction_record")
@Immutable
public class TransactionRecord {

    @EmbeddedId
    private TransactionRecordId key;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @Column(name = "source_row_number", nullable = false)
    private int sourceRowNumber;

    @Column(name = "raw_reference", nullable = false)
    private String rawReference;

    @Column(name = "normalized_reference", nullable = false)
    private String normalizedReference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "transaction_at")
    private @Nullable Instant transactionAt;

    @Column(name = "is_duplicate_in_source", nullable = false)
    private boolean duplicateInSource;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_values", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> rawValues;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected TransactionRecord() {} // JPA

    public TransactionRecordId getKey() {
        return key;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public int getSourceRowNumber() {
        return sourceRowNumber;
    }

    public String getRawReference() {
        return rawReference;
    }

    public String getNormalizedReference() {
        return normalizedReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public @Nullable Instant getTransactionAt() {
        return transactionAt;
    }

    public boolean isDuplicateInSource() {
        return duplicateInSource;
    }

    public Map<String, String> getRawValues() {
        return rawValues;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
