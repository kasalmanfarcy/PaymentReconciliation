package com.reconflow.paymentreconflow.exceptions.internal;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.exceptions.Discrepancy;
import com.reconflow.paymentreconflow.exceptions.DiscrepancyRecorder;
import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.RecordRef;
import com.reconflow.paymentreconflow.exceptions.RunExceptionSummary;
import com.reconflow.paymentreconflow.shared.fault.FaultInjector;
import com.reconflow.paymentreconflow.shared.fault.FaultPoints;
import com.reconflow.paymentreconflow.shared.id.Ids;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DiscrepancyRecorder} in SQL. The partial unique index {@code exception_case_active_uk}
 * (at most one non-RESOLVED case per key) is the real guarantee; this class only asks it politely.
 *
 * <p>Two statements, never one: {@code INSERT ... ON CONFLICT DO NOTHING RETURNING id}, then, if
 * nothing came back, a separate {@code SELECT} for the active case. Under READ COMMITTED, if a
 * concurrent transaction inserted the same key, our INSERT waits for it and then does nothing; the
 * follow-up SELECT is a new statement with a new snapshot, so it sees the winner's row. Merged into
 * one CTE statement, the SELECT part would use the snapshot from before the wait and could come
 * back empty.
 */
@Component
class JdbcDiscrepancyRecorder implements DiscrepancyRecorder {

    // ON CONFLICT (cols) WHERE predicate: how to target a *partial* unique index. PostgreSQL infers
    // exception_case_active_uk from the column list plus the index predicate.
    private static final String INSERT_CASE = """
            INSERT INTO exception_case
                   (id, organization_id, run_id, type, reason_code, discrepancy_key, status, currency, amount,
                    amount_difference)
            VALUES (:id, :org, :runId, :type, :reasonCode, :key, 'OPEN', :currency, :amount, :difference)
            ON CONFLICT (organization_id, discrepancy_key) WHERE status <> 'RESOLVED' DO NOTHING
            RETURNING id
            """;

    private static final String FIND_ACTIVE = """
            SELECT id FROM exception_case
             WHERE organization_id = :org AND discrepancy_key = :key AND status <> 'RESOLVED'
            """;

    private static final String INSERT_HISTORY = """
            INSERT INTO exception_status_history (id, organization_id, exception_id, from_status, to_status)
            VALUES (:id, :org, :caseId, NULL, 'OPEN')
            """;

    // An existing case may already link the record (another run saw it): nothing to do then.
    private static final String LINK_RECORD = """
            INSERT INTO exception_case_record (exception_id, transaction_record_id, transaction_date, organization_id)
            VALUES (?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    // A re-executed chunk may have linked the run already.
    private static final String LINK_RUN = """
            INSERT INTO exception_case_run (exception_id, run_id, organization_id, created, day)
            VALUES (:caseId, :runId, :org, :created, :day)
            ON CONFLICT DO NOTHING
            """;

    private static final String SUMMARIZE = """
            SELECT ec.type,
                   count(*)                                AS cases,
                   coalesce(sum(ec.amount), 0)             AS amount,
                   coalesce(sum(ec.amount_difference), 0)  AS net_difference
              FROM exception_case_run r
              JOIN exception_case ec ON ec.id = r.exception_id AND ec.organization_id = r.organization_id
             WHERE r.run_id = :runId AND r.organization_id = :org
             GROUP BY ec.type
            """;

    // The same totals for one anchor day: the PK (run_id, exception_id) narrows to the run first.
    private static final String SUMMARIZE_DAY = """
            SELECT ec.type,
                   count(*)                                AS cases,
                   coalesce(sum(ec.amount), 0)             AS amount,
                   coalesce(sum(ec.amount_difference), 0)  AS net_difference
              FROM exception_case_run r
              JOIN exception_case ec ON ec.id = r.exception_id AND ec.organization_id = r.organization_id
             WHERE r.run_id = :runId AND r.organization_id = :org AND r.day = :day
             GROUP BY ec.type
            """;

    /** A case can be resolved between our failed INSERT and our SELECT; then the INSERT succeeds. */
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcClient sql;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final FaultInjector faults;

    JdbcDiscrepancyRecorder(JdbcClient sql, JdbcTemplate jdbc, AuditService audit, FaultInjector faults) {
        this.sql = sql;
        this.jdbc = jdbc;
        this.audit = audit;
        this.faults = faults;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Recorded record(UUID organizationId, UUID runId, LocalDate day, List<Discrepancy> discrepancies) {
        int created = 0;
        int reobserved = 0;
        // Key order: every transaction locks keys in the same order, so two concurrent runs of one
        // organization can't each wait on a row the other inserted (deadlock).
        List<Discrepancy> ordered = discrepancies.stream()
                .sorted(Comparator.comparing(Discrepancy::discrepancyKey))
                .toList();
        for (Discrepancy d : ordered) {
            CaseId found = createOrFind(organizationId, runId, d);
            if (found.created()) {
                created++;
                // M9.4: the case exists but not its history: one transaction, so nothing partial survives.
                faults.at(FaultPoints.EXCEPTIONS_AFTER_CASE_INSERT);
                sql.sql(INSERT_HISTORY)
                        .param("id", Ids.newId())
                        .param("org", organizationId)
                        .param("caseId", found.id())
                        .update();
                // System actor: no user is bound in the worker, so actor_id is NULL.
                audit.record(AuditEntry.of(AuditAction.EXCEPTION_CREATED, AuditEntityType.EXCEPTION_CASE, found.id())
                        .with("type", d.type().name())
                        .with("discrepancyKey", d.discrepancyKey())
                        .with("runId", runId));
            } else {
                reobserved++;
            }
            jdbc.batchUpdate(
                    LINK_RECORD, d.records(), d.records().size(), (ps, ref) -> link(ps, found, ref, organizationId));
            sql.sql(LINK_RUN)
                    .param("caseId", found.id())
                    .param("runId", runId)
                    .param("org", organizationId)
                    .param("created", found.created())
                    .param("day", day)
                    .update();
        }
        return new Recorded(created, reobserved);
    }

    private record CaseId(UUID id, boolean created) {}

    private CaseId createOrFind(UUID organizationId, UUID runId, Discrepancy d) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<UUID> inserted = sql.sql(INSERT_CASE)
                    .param("id", Ids.newId())
                    .param("org", organizationId)
                    .param("runId", runId)
                    .param("type", d.type().name())
                    .param("reasonCode", d.reasonCode())
                    .param("key", d.discrepancyKey())
                    .param("currency", d.currency())
                    .param("amount", d.amount())
                    .param("difference", d.amountDifference())
                    .query(UUID.class)
                    .optional();
            if (inserted.isPresent()) {
                return new CaseId(inserted.get(), true);
            }
            Optional<UUID> active = sql.sql(FIND_ACTIVE)
                    .param("org", organizationId)
                    .param("key", d.discrepancyKey())
                    .query(UUID.class)
                    .optional();
            if (active.isPresent()) {
                return new CaseId(active.get(), false);
            }
        }
        throw new IllegalStateException("Could not create or find the active case for " + d.discrepancyKey());
    }

    private static void link(PreparedStatement ps, CaseId found, RecordRef ref, UUID organizationId)
            throws SQLException {
        ps.setObject(1, found.id());
        ps.setObject(2, ref.id());
        ps.setObject(3, ref.transactionDate());
        ps.setObject(4, organizationId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RunExceptionSummary summarize(UUID organizationId, UUID runId) {
        return totals(sql.sql(SUMMARIZE).param("runId", runId).param("org", organizationId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RunExceptionSummary summarizeDay(UUID organizationId, UUID runId, LocalDate day) {
        return totals(sql.sql(SUMMARIZE_DAY)
                .param("runId", runId)
                .param("org", organizationId)
                .param("day", day));
    }

    private static RunExceptionSummary totals(JdbcClient.StatementSpec statement) {
        Map<ExceptionType, RunExceptionSummary.Totals> byType = new EnumMap<>(ExceptionType.class);
        statement.query(rs -> {
            byType.put(
                    ExceptionType.valueOf(rs.getString("type")),
                    new RunExceptionSummary.Totals(
                            rs.getInt("cases"), rs.getBigDecimal("amount"), rs.getBigDecimal("net_difference")));
        });
        return new RunExceptionSummary(byType);
    }
}
