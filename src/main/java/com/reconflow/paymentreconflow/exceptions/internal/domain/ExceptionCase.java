package com.reconflow.paymentreconflow.exceptions.internal.domain;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A discrepancy someone has to work: the aggregate root of the exception workflow. Notes, status
 * history and record links hang off it but are <em>not</em> mapped as collections: they are
 * append-only and can grow, and the record links point into a partitioned table, so each is read
 * with its own query (no N+1, no huge entity).
 *
 * <p>Created only by {@code DiscrepancyRecorder} (SQL, M6), so there is no public constructor. No
 * setters: the four mutators are package-private and called only by {@link ExceptionStateMachine},
 * in this package, which checks the transition, the permission and the reason and writes history
 * and audit. The compiler enforces "one place"; an ArchUnit rule names it.
 *
 * <p>{@link Version}: Hibernate adds {@code WHERE version = ?} to every UPDATE and throws when it
 * matches no row (the <em>fast</em> race). On its own that is not enough over HTTP: each request
 * loads the case fresh, so the loaded version is always current. The command service compares the
 * client's {@code If-Match} with it explicitly (the <em>slow</em> race; ADR 0007).
 */
@Entity
@Table(name = "exception_case")
public class ExceptionCase {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    /** The run that raised the case; every run that saw it is in {@code exception_case_run}. */
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ExceptionType type;

    @Column(name = "reason_code", updatable = false)
    private String reasonCode;

    @Column(name = "discrepancy_key", nullable = false, updatable = false)
    private String discrepancyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExceptionStatus status;

    // CHAR(3): without the JDBC type Hibernate expects varchar and ddl-auto=validate fails on bpchar.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "amount_difference", updatable = false, precision = 19, scale = 4)
    private BigDecimal amountDifference;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_code")
    private ResolutionCode resolutionCode;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    // Primitive: Hibernate reads a null wrapper as "transient entity". The column defaults to 0.
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExceptionCase() {} // JPA only

    // ---- state changes: package-private, called only by ExceptionStateMachine -------------------

    void moveTo(ExceptionStatus target) {
        this.status = target;
    }

    void assignTo(@Nullable UUID userIdOrNull) {
        this.assignedTo = userIdOrNull;
    }

    /** V1's CHECK requires the four fields together, exactly while RESOLVED. */
    void markResolved(ResolutionCode code, @Nullable String note, UUID by, Instant at) {
        this.resolutionCode = code;
        this.resolutionNote = note;
        this.resolvedBy = by;
        this.resolvedAt = at;
    }

    /** On reopen; the previous resolution lives on in exception_status_history (M7.4). */
    void clearResolution() {
        this.resolutionCode = null;
        this.resolutionNote = null;
        this.resolvedBy = null;
        this.resolvedAt = null;
    }

    // ---- reads -----------------------------------------------------------------------------------

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getRunId() {
        return runId;
    }

    public ExceptionType getType() {
        return type;
    }

    public @Nullable String getReasonCode() {
        return reasonCode;
    }

    public String getDiscrepancyKey() {
        return discrepancyKey;
    }

    public ExceptionStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public @Nullable BigDecimal getAmountDifference() {
        return amountDifference;
    }

    public @Nullable UUID getAssignedTo() {
        return assignedTo;
    }

    public @Nullable ResolutionCode getResolutionCode() {
        return resolutionCode;
    }

    public @Nullable String getResolutionNote() {
        return resolutionNote;
    }

    public @Nullable UUID getResolvedBy() {
        return resolvedBy;
    }

    public @Nullable Instant getResolvedAt() {
        return resolvedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** For unit tests of the state machine: a case in a given state, never persisted. */
    static ExceptionCase forTest(UUID id, UUID organizationId, ExceptionStatus status, long version) {
        var c = new ExceptionCase();
        c.id = id;
        c.organizationId = organizationId;
        c.status = status;
        c.version = version;
        c.type = ExceptionType.AMOUNT_MISMATCH;
        c.discrepancyKey = "AMOUNT_MISMATCH:PAY-1:PAYMENT:AED";
        c.currency = "AED";
        c.amount = BigDecimal.ONE;
        return c;
    }
}
