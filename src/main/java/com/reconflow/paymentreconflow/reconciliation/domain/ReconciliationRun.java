package com.reconflow.paymentreconflow.reconciliation.domain;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * One execution of the matching rules over a period, a currency and a source pair: the unit of
 * work, of idempotency (ADR 0006) and of reporting.
 *
 * <p>No setters. The status changes only through {@link #transitionTo}, called only by
 * {@code RunLifecycle} (an ArchUnit rule checks it), which also writes the audit event in the same
 * transaction. The checkpoint {@code last_completed_date} is <em>not</em> written through this
 * entity: each day-chunk advances it with one SQL UPDATE in the chunk's own transaction, so a
 * stale copy of the entity can never overwrite it. A transaction therefore uses either this entity
 * or SQL updates on the run, never both.
 *
 * <p>No {@code @Version}: transitions take a row lock ({@code SELECT ... FOR UPDATE}) instead, like
 * import batches. They are rare and short.
 */
@Entity
@Table(name = "reconciliation_run")
public class ReconciliationRun implements Persistable<UUID> {

    /** {@code failure_reason} is for users: short, and never a stack trace. */
    static final int MAX_FAILURE_REASON = 1000;

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    /** {@code client:<header>} or {@code auto:<fingerprint>} (ADR 0006). */
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    // CHAR(64): without the JDBC type Hibernate expects varchar and ddl-auto=validate fails on bpchar.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_fingerprint", nullable = false, updatable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    /** Inclusive. */
    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(name = "left_source", nullable = false, updatable = false)
    private SourceType leftSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "right_source", nullable = false, updatable = false)
    private SourceType rightSource;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    /** The resume checkpoint; written by SQL in each chunk's transaction, read-only here. */
    @Column(name = "last_completed_date", insertable = false, updatable = false)
    private @Nullable LocalDate lastCompletedDate;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "matched_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal matchedAmount = BigDecimal.ZERO;

    @Column(name = "mismatch_count", nullable = false)
    private int mismatchCount;

    @Column(name = "mismatch_net_difference", nullable = false, precision = 19, scale = 4)
    private BigDecimal mismatchNetDifference = BigDecimal.ZERO;

    @Column(name = "unmatched_left_count", nullable = false)
    private int unmatchedLeftCount;

    @Column(name = "unmatched_left_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unmatchedLeftAmount = BigDecimal.ZERO;

    @Column(name = "unmatched_right_count", nullable = false)
    private int unmatchedRightCount;

    @Column(name = "unmatched_right_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unmatchedRightAmount = BigDecimal.ZERO;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "currency_mismatch_count", nullable = false)
    private int currencyMismatchCount;

    @Column(name = "excluded_count", nullable = false)
    private int excludedCount;

    @Column(name = "failure_reason")
    private @Nullable String failureReason;

    @Column(name = "triggered_by", nullable = false, updatable = false)
    private UUID triggeredBy;

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

    protected ReconciliationRun() {} // JPA

    /** A run just requested: PENDING, no checkpoint, zero totals. */
    public static ReconciliationRun pending(
            UUID id,
            UUID organizationId,
            String idempotencyKey,
            String requestFingerprint,
            LocalDate periodStart,
            LocalDate periodEnd,
            SourceType leftSource,
            SourceType rightSource,
            String currency,
            UUID triggeredBy,
            Instant now) {
        var r = new ReconciliationRun();
        r.id = id;
        r.organizationId = organizationId;
        r.idempotencyKey = idempotencyKey;
        r.requestFingerprint = requestFingerprint;
        r.periodStart = periodStart;
        r.periodEnd = periodEnd;
        r.leftSource = leftSource;
        r.rightSource = rightSource;
        r.currency = currency;
        r.status = RunStatus.PENDING;
        r.triggeredBy = triggeredBy;
        r.createdAt = now;
        r.updatedAt = now;
        r.isNew = true;
        return r;
    }

    /**
     * The only way the status changes: checked against {@link RunStatus}'s table, stamping the
     * timestamps that go with it. Callers ({@code RunLifecycle}) hold the row lock and audit.
     *
     * @throws InvalidRunTransitionException if the state machine doesn't allow it
     */
    public void transitionTo(RunStatus next, Instant now) {
        if (!status.canMoveTo(next)) {
            throw new InvalidRunTransitionException(id, status, next);
        }
        switch (next) {
            case RUNNING -> startedAt = now;
            case COMPLETED, FAILED -> completedAt = now;
            case PENDING -> {
                // A retry: the checkpoint stays (the next job resumes after it), the failure goes.
                failureReason = null;
                completedAt = null;
            }
        }
        status = next;
        updatedAt = now;
    }

    /** RUNNING → COMPLETED with the summary, in one step. */
    public void complete(RunSummary summary, Instant now) {
        transitionTo(RunStatus.COMPLETED, now);
        matchedCount = summary.matchedCount();
        matchedAmount = summary.matchedAmount();
        mismatchCount = summary.mismatchCount();
        mismatchNetDifference = summary.mismatchNetDifference();
        unmatchedLeftCount = summary.unmatchedLeftCount();
        unmatchedLeftAmount = summary.unmatchedLeftAmount();
        unmatchedRightCount = summary.unmatchedRightCount();
        unmatchedRightAmount = summary.unmatchedRightAmount();
        duplicateCount = summary.duplicateCount();
        currencyMismatchCount = summary.currencyMismatchCount();
        excludedCount = summary.excludedCount();
    }

    /** → FAILED with the reason users see, truncated to {@value #MAX_FAILURE_REASON} characters. */
    public void fail(String reason, Instant now) {
        transitionTo(RunStatus.FAILED, now);
        failureReason = reason.length() <= MAX_FAILURE_REASON ? reason : reason.substring(0, MAX_FAILURE_REASON);
    }

    /** The summary columns; meaningful only once COMPLETED. */
    public RunSummary summary() {
        return new RunSummary(
                matchedCount,
                matchedAmount,
                mismatchCount,
                mismatchNetDifference,
                unmatchedLeftCount,
                unmatchedLeftAmount,
                unmatchedRightCount,
                unmatchedRightAmount,
                duplicateCount,
                currencyMismatchCount,
                excludedCount);
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public SourceType getLeftSource() {
        return leftSource;
    }

    public SourceType getRightSource() {
        return rightSource;
    }

    public String getCurrency() {
        return currency;
    }

    public RunStatus getStatus() {
        return status;
    }

    public @Nullable LocalDate getLastCompletedDate() {
        return lastCompletedDate;
    }

    public @Nullable String getFailureReason() {
        return failureReason;
    }

    public UUID getTriggeredBy() {
        return triggeredBy;
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
