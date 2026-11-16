package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.exceptions.DiscrepancyRecorder;
import com.reconflow.paymentreconflow.ingestion.ImportActivity;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRun;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRunRepository;
import com.reconflow.paymentreconflow.reconciliation.domain.RunStatus;
import com.reconflow.paymentreconflow.reconciliation.domain.RunSummary;
import com.reconflow.paymentreconflow.reconciliation.engine.RunContext;
import com.reconflow.paymentreconflow.reconciliation.engine.SqlScripts;
import com.reconflow.paymentreconflow.shared.jobs.PermanentJobFailure;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place a run's status changes (reconciliation-rules.md §7.2), each change audited in the
 * same transaction ({@code AuditService} is MANDATORY, so a call outside one fails in the first test
 * that runs it). Every method joins the caller's transaction: the executor and the services own
 * the boundaries.
 *
 * <p>Within one transaction either the entity or SQL touches the run row, never both: the chunk
 * methods ({@link #lockForChunk}, {@link #advanceCheckpoint}) use SQL; the transitions use the
 * entity, loaded under a row lock.
 */
@Component
public class RunLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunLifecycle.class);

    private static final String LOCK_RUN_FOR_CHUNK = SqlScripts.load("lock_run_for_chunk.sql");
    private static final String ADVANCE_CHECKPOINT = SqlScripts.load("advance_checkpoint.sql");
    private static final String SUMMARIZE_MATCHES = SqlScripts.load("summarize_matches.sql");
    private static final String COUNT_EXCLUDED = SqlScripts.load("count_excluded.sql");

    private final ReconciliationRunRepository runs;
    private final JdbcClient jdbc;
    private final DiscrepancyRecorder discrepancies;
    private final ImportActivity imports;
    private final AuditService audit;
    private final Clock clock;

    RunLifecycle(
            ReconciliationRunRepository runs,
            JdbcClient jdbc,
            DiscrepancyRecorder discrepancies,
            ImportActivity imports,
            AuditService audit,
            Clock clock) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.discrepancies = discrepancies;
        this.imports = imports;
        this.audit = audit;
        this.clock = clock;
    }

    /** What the worker should do with a claimed RECONCILE job. */
    public sealed interface Start {

        /** Work from {@code firstDay} (the day after the checkpoint) through the period's end. */
        record Run(RunContext context, LocalDate firstDay) implements Start {}

        /** The run is already COMPLETED or FAILED: a harmless repeat of the job. */
        record Skip(RunStatus status) implements Start {}

        /** An import of the organization is still in progress: try again later. */
        record Defer() implements Start {}
    }

    /**
     * PENDING → RUNNING ({@code RUN_STARTED}), or resume a RUNNING run after a crash (not a
     * transition, not audited). A PENDING run waits while an import is in progress, because a run
     * over a half-imported file would report every payment missing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Start start(UUID organizationId, UUID runId) {
        ReconciliationRun run = lock(organizationId, runId);
        switch (run.getStatus()) {
            case COMPLETED, FAILED -> {
                log.info("Run {} is already {}; nothing to do", runId, run.getStatus());
                return new Start.Skip(run.getStatus());
            }
            case PENDING -> {
                if (imports.importInProgress(organizationId)) {
                    return new Start.Defer();
                }
                run.transitionTo(RunStatus.RUNNING, clock.instant());
                audit.record(AuditEntry.of(AuditAction.RUN_STARTED, AuditEntityType.RECONCILIATION_RUN, runId)
                        .with("resumeFrom", firstDay(run).toString())
                        .with("lastCompletedDate", dateOrNull(run.getLastCompletedDate())));
                log.info("Starting run {} from {}", runId, firstDay(run));
            }
            case RUNNING -> log.info("Resuming run {} after {}", runId, run.getLastCompletedDate());
        }
        return new Start.Run(context(run), firstDay(run));
    }

    /**
     * Fencing for a day-chunk: locks the run row and checks it is RUNNING with its checkpoint on
     * the previous day. A worker whose view is stale (another one moved the run on) stops here,
     * before writing anything; the row lock also serializes two workers on one run.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForChunk(RunContext run, LocalDate day) {
        Map<String, Object> row = jdbc.sql(LOCK_RUN_FOR_CHUNK)
                .param("runId", run.runId())
                .param("org", run.organizationId())
                .query()
                .singleRow();
        String status = (String) row.get("status");
        LocalDate checkpoint =
                row.get("last_completed_date") == null ? null : ((Date) row.get("last_completed_date")).toLocalDate();
        LocalDate expected = day.equals(run.periodStart()) ? null : day.minusDays(1);
        if (!RunStatus.RUNNING.name().equals(status) || !Objects.equals(checkpoint, expected)) {
            throw new StaleRunOwnershipException(run.runId(), day, status, checkpoint);
        }
    }

    /** The checkpoint, in the chunk's transaction, by SQL (see the class comment). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void advanceCheckpoint(RunContext run, LocalDate day) {
        int updated = jdbc.sql(ADVANCE_CHECKPOINT)
                .param("day", day)
                .param("runId", run.runId())
                .param("org", run.organizationId())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Run " + run.runId() + " is no longer RUNNING");
        }
    }

    /**
     * RUNNING → COMPLETED with the summary ({@code RUN_COMPLETED}). The summary is computed from
     * scratch here, from the run's matches and the cases it observed, so a resumed or retried run
     * gets exactly the numbers of a clean one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RunSummary complete(RunContext context) {
        RunSummary summary = summarize(context);
        ReconciliationRun run = lock(context.organizationId(), context.runId());
        if (run.getStatus() != RunStatus.RUNNING || !context.periodEnd().equals(run.getLastCompletedDate())) {
            throw new StaleRunOwnershipException(
                    run.getId(), context.periodEnd(), run.getStatus().name(), run.getLastCompletedDate());
        }
        run.complete(summary, clock.instant());
        audit.record(AuditEntry.of(AuditAction.RUN_COMPLETED, AuditEntityType.RECONCILIATION_RUN, run.getId())
                .with("matchedCount", summary.matchedCount())
                .with("matchedAmount", summary.matchedAmount())
                .with("mismatchCount", summary.mismatchCount())
                .with("mismatchNetDifference", summary.mismatchNetDifference())
                .with("unmatchedLeftCount", summary.unmatchedLeftCount())
                .with("unmatchedLeftAmount", summary.unmatchedLeftAmount())
                .with("unmatchedRightCount", summary.unmatchedRightCount())
                .with("unmatchedRightAmount", summary.unmatchedRightAmount())
                .with("duplicateCount", summary.duplicateCount())
                .with("currencyMismatchCount", summary.currencyMismatchCount())
                .with("excludedCount", summary.excludedCount()));
        log.info("Completed run {}: {}", run.getId(), summary);
        return summary;
    }

    /**
     * PENDING or RUNNING → FAILED ({@code RUN_FAILED}), from the queue's transaction that also marks
     * the job FAILED. A run already terminal is left alone. The checkpoint stays, so a retry
     * resumes after it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fail(UUID organizationId, UUID runId, String reason) {
        runs.lockByIdAndOrganizationId(runId, organizationId).ifPresent(run -> {
            if (run.getStatus().isTerminal()) {
                return;
            }
            run.fail(reason, clock.instant());
            audit.record(AuditEntry.of(AuditAction.RUN_FAILED, AuditEntityType.RECONCILIATION_RUN, runId)
                    .with("reason", run.getFailureReason())
                    .with("lastCompletedDate", dateOrNull(run.getLastCompletedDate())));
        });
    }

    private RunSummary summarize(RunContext run) {
        Map<String, Object> matched = jdbc.sql(SUMMARIZE_MATCHES)
                .param("runId", run.runId())
                .param("org", run.organizationId())
                .query()
                .singleRow();
        Integer excluded = jdbc.sql(COUNT_EXCLUDED)
                .param("org", run.organizationId())
                .param("periodStart", run.periodStart())
                .param("periodEnd", run.periodEnd())
                .param("leftSource", run.leftSource().name())
                .param("rightSource", run.rightSource().name())
                .param("currency", run.currency())
                .query(Integer.class)
                .single();
        return RunSummaries.of(
                run.leftSource(),
                ((Number) matched.get("matched_count")).intValue(),
                (BigDecimal) matched.get("matched_amount"),
                discrepancies.summarize(run.organizationId(), run.runId()),
                excluded);
    }

    private ReconciliationRun lock(UUID organizationId, UUID runId) {
        // Permanent: a job for a run that doesn't exist (in this organization) will never work.
        return runs.lockByIdAndOrganizationId(runId, organizationId)
                .orElseThrow(() -> new PermanentJobFailure("run-not-found"));
    }

    static RunContext context(ReconciliationRun run) {
        return new RunContext(
                run.getOrganizationId(),
                run.getId(),
                run.getPeriodStart(),
                run.getPeriodEnd(),
                run.getLeftSource(),
                run.getRightSource(),
                run.getCurrency());
    }

    private static LocalDate firstDay(ReconciliationRun run) {
        return run.getLastCompletedDate() == null
                ? run.getPeriodStart()
                : run.getLastCompletedDate().plusDays(1);
    }

    private static @Nullable String dateOrNull(@Nullable LocalDate date) {
        return date == null ? null : date.toString();
    }
}
