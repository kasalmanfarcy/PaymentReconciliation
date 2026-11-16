package com.reconflow.paymentreconflow.reconciliation.application;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.ingestion.ImportActivity;
import com.reconflow.paymentreconflow.ledger.SourceType;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRun;
import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRunRepository;
import com.reconflow.paymentreconflow.shared.context.CurrentUser;
import com.reconflow.paymentreconflow.shared.context.TenantContext;
import com.reconflow.paymentreconflow.shared.error.ConflictException;
import com.reconflow.paymentreconflow.shared.error.NotFoundException;
import com.reconflow.paymentreconflow.shared.error.ProblemCodes;
import com.reconflow.paymentreconflow.shared.error.UniqueViolations;
import com.reconflow.paymentreconflow.shared.error.ValidationFailedException;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.jobs.JobQueue;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Requesting a run (M6.11, M6.14). Idempotent: the same request, or the same
 * {@code Idempotency-Key}, returns the existing run instead of starting a second one (ADR 0006).
 *
 * <p>Races are settled by the database, never by check-then-insert in Java:
 * <ul>
 *   <li>{@code INSERT ... ON CONFLICT (organization_id, idempotency_key) DO NOTHING RETURNING id}.
 *       Two identical requests at once: the second insert waits for the first to commit, then does
 *       nothing and returns no row, and a <em>new</em> statement reads the winner. Catching a
 *       unique violation instead would not work in one transaction: PostgreSQL aborts the whole
 *       transaction after an error.</li>
 *   <li>{@code reconciliation_run_active_scope_uk} allows one PENDING/RUNNING run per organization
 *       and scope. Its violation does abort the transaction, so it is caught <em>outside</em> it,
 *       and a second transaction decides: an identical request that lost the race (its key now
 *       exists) gets the existing run; anything else gets {@code 409 run-in-progress}.</li>
 * </ul>
 * The run, its RECONCILE job and its audit event commit in one transaction: there is no window
 * where a run exists without the job that will run it.
 */
@Service
public class ReconciliationRunService {

    static final int MAX_PERIOD_DAYS = 92;
    static final String ACTIVE_SCOPE_UK = "reconciliation_run_active_scope_uk";

    private static final String INSERT_RUN = """
            INSERT INTO reconciliation_run
                   (id, organization_id, idempotency_key, request_fingerprint, period_start, period_end,
                    left_source, right_source, currency, status, triggered_by)
            VALUES (:id, :org, :key, :fingerprint, :periodStart, :periodEnd, :left, :right, :currency, 'PENDING', :user)
            ON CONFLICT (organization_id, idempotency_key) DO NOTHING
            RETURNING id
            """;

    private static final String FIND_ACTIVE_IN_SCOPE = """
            SELECT id FROM reconciliation_run
             WHERE organization_id = :org AND currency = :currency AND left_source = :left
               AND right_source = :right AND period_start = :periodStart AND period_end = :periodEnd
               AND status IN ('PENDING', 'RUNNING')
            """;

    /** Whether the request created the run (202) or found it (200). */
    public record Created(RunView run, boolean isNew) {}

    private record Scope(
            UUID organizationId,
            LocalDate periodStart,
            LocalDate periodEnd,
            SourceType left,
            SourceType right,
            String currency) {}

    private final ReconciliationRunRepository runs;
    private final JdbcClient jdbc;
    private final JobQueue jobs;
    private final ImportActivity imports;
    private final AuditService audit;
    private final TenantContext tenant;
    private final CurrentUser currentUser;
    private final TransactionTemplate tx;
    private final RunLifecycle lifecycle;

    ReconciliationRunService(
            ReconciliationRunRepository runs,
            JdbcClient jdbc,
            JobQueue jobs,
            ImportActivity imports,
            AuditService audit,
            TenantContext tenant,
            CurrentUser currentUser,
            TransactionTemplate tx,
            RunLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.runs = runs;
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.imports = imports;
        this.audit = audit;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.tx = tx;
    }

    public Created create(RunRequest request, @Nullable String clientKey) {
        Scope scope = validate(request, clientKey);
        String fingerprint = RunKeys.fingerprint(
                scope.organizationId(),
                scope.periodStart(),
                scope.periodEnd(),
                scope.left(),
                scope.right(),
                scope.currency());
        String key = RunKeys.key(clientKey, fingerprint);
        try {
            return tx.execute(s -> createOrFind(scope, key, fingerprint, clientKey != null));
        } catch (DataIntegrityViolationException e) {
            if (!UniqueViolations.isViolationOf(e, ACTIVE_SCOPE_UK)) {
                throw e;
            }
            // The first transaction is gone (aborted). Decide in a fresh one.
            return tx.execute(
                    s -> existing(scope.organizationId(), key, fingerprint).orElseThrow(() -> runInProgress(scope)));
        }
    }

    private Created createOrFind(Scope scope, String key, String fingerprint, boolean clientKey) {
        // Fast path for a repeat: answered even while an import is in progress, because it starts nothing.
        Optional<Created> found = existing(scope.organizationId(), key, fingerprint);
        if (found.isPresent()) {
            return found.get();
        }
        if (imports.importInProgress(scope.organizationId())) {
            throw new ConflictException(
                    ProblemCodes.IMPORT_IN_PROGRESS,
                    "An import is still being processed; start the run when it has finished.");
        }
        UUID id = Ids.newId();
        Optional<UUID> inserted = jdbc.sql(INSERT_RUN)
                .param("id", id)
                .param("org", scope.organizationId())
                .param("key", key)
                .param("fingerprint", fingerprint)
                .param("periodStart", scope.periodStart())
                .param("periodEnd", scope.periodEnd())
                .param("left", scope.left().name())
                .param("right", scope.right().name())
                .param("currency", scope.currency())
                .param("user", currentUser.id())
                .query(UUID.class)
                .optional();
        if (inserted.isEmpty()) {
            // A concurrent identical request committed first; this new statement sees its row.
            return existing(scope.organizationId(), key, fingerprint).orElseThrow();
        }
        jobs.enqueue(scope.organizationId(), ReconcileJobs.TYPE, ReconcileJobs.payload(id));
        audit.record(AuditEntry.of(AuditAction.RUN_REQUESTED, AuditEntityType.RECONCILIATION_RUN, id)
                .with("periodStart", scope.periodStart().toString())
                .with("periodEnd", scope.periodEnd().toString())
                .with("currency", scope.currency())
                .with("leftSource", scope.left().name())
                .with("rightSource", scope.right().name())
                .with("idempotencyKeyType", clientKey ? "client" : "auto"));
        ReconciliationRun run =
                runs.findByIdAndOrganizationId(id, scope.organizationId()).orElseThrow();
        return new Created(RunView.of(run), true);
    }

    /**
     * {@code POST /reconciliation-runs/{id}/retry} (M6.12): a FAILED run goes back to PENDING with a
     * new RECONCILE job, in one transaction with the run row locked (two retries at once are
     * serialized; the second finds PENDING and gets 409). The run keeps its checkpoint.
     */
    public RunView retry(UUID id, String reason) {
        UUID organizationId = tenant.organizationId();
        try {
            return tx.execute(s -> {
                ReconciliationRun run = runs.lockByIdAndOrganizationId(id, organizationId)
                        .orElseThrow(() -> new NotFoundException("Reconciliation run", id));
                lifecycle.requestRetry(run, reason);
                jobs.enqueue(organizationId, ReconcileJobs.TYPE, ReconcileJobs.payload(id));
                return RunView.of(run);
            });
        } catch (DataIntegrityViolationException e) {
            if (!UniqueViolations.isViolationOf(e, ACTIVE_SCOPE_UK)) {
                throw e;
            }
            ReconciliationRun run = tx.execute(s -> runs.findByIdAndOrganizationId(id, organizationId)
                    .orElseThrow(() -> new NotFoundException("Reconciliation run", id)));
            throw runInProgress(new Scope(
                    organizationId,
                    run.getPeriodStart(),
                    run.getPeriodEnd(),
                    run.getLeftSource(),
                    run.getRightSource(),
                    run.getCurrency()));
        }
    }

    /** The run this key already names: the same request gets it back, a different one gets 422. */
    private Optional<Created> existing(UUID organizationId, String key, String fingerprint) {
        return runs.findByOrganizationIdAndIdempotencyKey(organizationId, key).map(run -> {
            if (!run.getRequestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReusedException();
            }
            return new Created(RunView.of(run), false);
        });
    }

    private ConflictException runInProgress(Scope scope) {
        Optional<UUID> active = jdbc.sql(FIND_ACTIVE_IN_SCOPE)
                .param("org", scope.organizationId())
                .param("currency", scope.currency())
                .param("left", scope.left().name())
                .param("right", scope.right().name())
                .param("periodStart", scope.periodStart())
                .param("periodEnd", scope.periodEnd())
                .query(UUID.class)
                .optional();
        return new ConflictException(
                ProblemCodes.RUN_IN_PROGRESS,
                "A run for this period, currency and sources is already pending or running.",
                active.<Map<String, Object>>map(id -> Map.of("existingRunId", id))
                        .orElse(Map.of()));
    }

    /** api-design.md §8.8, plus the header (§5). */
    private Scope validate(RunRequest request, @Nullable String clientKey) {
        if (clientKey != null && !RunKeys.isValidClientKey(clientKey)) {
            throw new ValidationFailedException(
                    "Idempotency-Key",
                    "invalid-idempotency-key",
                    "Idempotency-Key must be 1-128 printable ASCII characters");
        }
        if (request.periodEnd().isBefore(request.periodStart())) {
            throw new ValidationFailedException(
                    "periodEnd", "must-not-be-before-start", "periodEnd must be on or after periodStart");
        }
        if (ChronoUnit.DAYS.between(request.periodStart(), request.periodEnd()) + 1 > MAX_PERIOD_DAYS) {
            throw new ValidationFailedException(
                    "periodEnd", "period-too-long", "A run can cover at most " + MAX_PERIOD_DAYS + " days");
        }
        String currency = request.currency().strip().toUpperCase(Locale.ROOT);
        if (!isIsoCurrency(currency)) {
            throw new ValidationFailedException("currency", "invalid-currency", "currency must be an ISO-4217 code");
        }
        SourceType left = request.leftSource() == null ? SourceType.INTERNAL_LEDGER : request.leftSource();
        SourceType right = request.rightSource() == null ? SourceType.GATEWAY : request.rightSource();
        if (left == right) {
            throw new ValidationFailedException(
                    "rightSource", "sources-must-differ", "leftSource and rightSource must be different sources");
        }
        return new Scope(tenant.organizationId(), request.periodStart(), request.periodEnd(), left, right, currency);
    }

    private static boolean isIsoCurrency(String code) {
        if (!code.matches("[A-Z]{3}")) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
