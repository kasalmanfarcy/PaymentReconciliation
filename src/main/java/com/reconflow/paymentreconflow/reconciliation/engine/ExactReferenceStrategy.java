package com.reconflow.paymentreconflow.reconciliation.engine;

import com.reconflow.paymentreconflow.exceptions.Discrepancy;
import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.ReasonCodes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * v1 matching (ADR 0012): set-based SQL inside PostgreSQL, one anchor day per call. The data never
 * leaves the database except for the groups that become exceptions (about 2%).
 *
 * <p>Group, then classify; never pair. Every participating record belongs to exactly one group
 * {@code (normalized_reference, transaction_type)}, every group gets exactly one outcome, and every
 * group has exactly one anchor day, so "every record in exactly one place" holds by construction.
 */
@Component
public class ExactReferenceStrategy implements MatchingStrategy {

    private static final String CREATE_GROUP_TABLE = SqlScripts.load("create_group_table.sql");
    private static final String CLASSIFY_GROUPS = SqlScripts.load("classify_groups.sql");
    private static final String INSERT_MATCHES = SqlScripts.load("insert_matches.sql");
    private static final String SELECT_EXCEPTION_GROUPS = SqlScripts.load("select_exception_groups.sql");
    private static final String SELECT_COUNTERPARTS_NOT_SUCCESS =
            SqlScripts.load("select_counterparts_not_success.sql");

    private final JdbcClient jdbc;

    ExactReferenceStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "EXACT_REFERENCE";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DayResult reconcileDay(RunContext run, LocalDate day) {
        // Normally gone already (ON COMMIT DROP); dropped explicitly in case one transaction
        // classifies two days. pg_temp so it can never touch a real table of that name.
        jdbc.sql("DROP TABLE IF EXISTS pg_temp.recon_group").update();
        jdbc.sql(CREATE_GROUP_TABLE).update();
        jdbc.sql(CLASSIFY_GROUPS)
                .param("org", run.organizationId())
                .param("day", day)
                .param("periodStart", run.periodStart())
                .param("periodEnd", run.periodEnd())
                .param("leftSource", run.leftSource().name())
                .param("rightSource", run.rightSource().name())
                .param("currency", run.currency())
                .update();
        int matches = jdbc.sql(INSERT_MATCHES)
                .param("org", run.organizationId())
                .param("runId", run.runId())
                .update();
        List<GroupRow> exceptionGroups =
                jdbc.sql(SELECT_EXCEPTION_GROUPS).query(GroupRow::map).list();

        Set<GroupKey> counterpartNotSuccess = counterpartsNotSuccess(run, exceptionGroups);

        List<Discrepancy> discrepancies = new ArrayList<>(exceptionGroups.size());
        for (GroupRow group : exceptionGroups) {
            discrepancies.add(toDiscrepancy(run, group, counterpartNotSuccess));
        }
        discrepancies.sort(Comparator.comparing(Discrepancy::discrepancyKey));
        return new DayResult(matches, discrepancies);
    }

    private record GroupKey(String normalizedReference, String transactionType) {}

    /** One extra query per chunk, and only when the chunk has missing groups at all. */
    private Set<GroupKey> counterpartsNotSuccess(RunContext run, List<GroupRow> groups) {
        boolean anyMissing = groups.stream()
                .anyMatch(g ->
                        g.outcome().equals("MISSING_IN_GATEWAY") || g.outcome().equals("MISSING_IN_LEDGER"));
        if (!anyMissing) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql(SELECT_COUNTERPARTS_NOT_SUCCESS)
                .param("org", run.organizationId())
                .param("periodStart", run.periodStart())
                .param("periodEnd", run.periodEnd())
                .param("leftSource", run.leftSource().name())
                .param("rightSource", run.rightSource().name())
                .query((rs, n) -> new GroupKey(rs.getString("normalized_reference"), rs.getString("transaction_type")))
                .list());
    }

    private static @Nullable String reasonCode(
            ExceptionType type, GroupRow group, Set<GroupKey> counterpartNotSuccess) {
        return switch (type) {
            case MISSING_IN_GATEWAY, MISSING_IN_LEDGER ->
                counterpartNotSuccess.contains(new GroupKey(group.normalizedReference(), group.transactionType()))
                        ? ReasonCodes.COUNTERPART_NOT_SUCCESS
                        : null;
            default -> null;
        };
    }

    private static Discrepancy toDiscrepancy(RunContext run, GroupRow group, Set<GroupKey> counterpartNotSuccess) {
        if (group.outcome().equals("INVARIANT_VIOLATED")) {
            // A group with no records can't exist; if it does, the data or the SQL is broken and
            // retrying won't help (phase-M6 §M6.10: permanent, fail the run).
            throw new EngineInvariantViolation("group " + group.normalizedReference() + " has no records");
        }
        ExceptionType type = ExceptionType.valueOf(group.outcome());
        BigDecimal amount = group.ledgerAmount() != null ? group.ledgerAmount() : group.gatewayAmount();
        @Nullable
        BigDecimal difference = type == ExceptionType.AMOUNT_MISMATCH
                // Sign convention: gateway − ledger; negative means the gateway paid less.
                ? group.gatewayAmount().subtract(group.ledgerAmount())
                : null;
        return new Discrepancy(
                type,
                Discrepancy.key(type, group.normalizedReference(), group.transactionType(), group.currencies()),
                reasonCode(type, group, counterpartNotSuccess),
                run.currency(),
                amount,
                difference,
                group.allRecords());
    }
}
