package com.reconflow.paymentreconflow.reconciliation.archive;

import com.reconflow.paymentreconflow.audit.AuditAction;
import com.reconflow.paymentreconflow.audit.AuditEntityType;
import com.reconflow.paymentreconflow.audit.AuditEntry;
import com.reconflow.paymentreconflow.audit.AuditService;
import com.reconflow.paymentreconflow.exceptions.CaseLinkArchive;
import com.reconflow.paymentreconflow.ledger.RecordArchive;
import com.reconflow.paymentreconflow.shared.context.TenantScope;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.storage.CsvArchive;
import com.reconflow.paymentreconflow.shared.storage.CsvArchive.ArchivedFile;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Archival of old months (M9.16, docs/scalability.md §5.3): keep 13 months hot in PostgreSQL;
 * export anything older to object storage, verify it, then detach and drop the partition. Without it
 * the hot database grows by ~410 GB a year (scalability.md §2.2).
 *
 * <p>Per month, in this order (each step only after the previous succeeded):
 *
 * <ol>
 *   <li><b>Refuse</b> the month if any not-RESOLVED case links one of its records: an investigator
 *       still needs those records side by side. It waits for the next monthly run.
 *   <li><b>Per organization, one transaction</b> (bound, so row-level security applies): export
 *       its records, its matches touching the month and its case links into the month as gzipped
 *       CSV; <b>verify</b> each file read back from storage against the same figures computed in the
 *       database (row counts, and the amount total for records); write the manifest; delete the
 *       matches and links (their foreign keys into the partition would refuse the drop); audit.
 *       Any mismatch or failure rolls the organization's transaction back: nothing is deleted.
 *   <li><b>Drop</b> the partition, once every organization in it has a manifest.
 * </ol>
 *
 * <p>Restartable: an organization that already has its manifest for the month is skipped, so a run
 * that failed half way just continues. Lives in {@code reconciliation} because it is the module
 * allowed to see records (ledger), matches (its own) and case links (exceptions' API) together.
 * Archived data is not served by the API; restoring a file for an investigation is the runbook in
 * docs/runbooks/archival.md.
 */
@Component
public class PartitionArchival {

    /** Months kept hot: the current one and the 12 before it. */
    public static final int HOT_MONTHS = 13;

    private static final Logger log = LoggerFactory.getLogger(PartitionArchival.class);

    private static final String MATCH_RANGE = " FROM reconciliation_match m WHERE m.organization_id = ?"
            + " AND ((m.ledger_txn_date >= ? AND m.ledger_txn_date < ?)"
            + " OR (m.gateway_txn_date >= ? AND m.gateway_txn_date < ?))";

    /** What happened to one month. */
    public enum Outcome {
        ARCHIVED,
        WAITING_FOR_ACTIVE_CASES,
        NOT_DUE
    }

    private final RecordArchive records;
    private final CaseLinkArchive caseLinks;
    private final CsvArchive csv;
    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final Clock clock;

    PartitionArchival(
            RecordArchive records,
            CaseLinkArchive caseLinks,
            CsvArchive csv,
            JdbcTemplate jdbc,
            TenantScope tenantScope,
            TransactionTemplate tx,
            AuditService audit,
            Clock clock) {
        this.records = records;
        this.caseLinks = caseLinks;
        this.csv = csv;
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.tx = tx;
        this.audit = audit;
        this.clock = clock;
    }

    /** Monthly, on worker nodes, at night (dropping a partition briefly locks the parent table). */
    @Scheduled(cron = "${reconflow.archive.cron:0 30 3 2 * *}", zone = "UTC")
    void monthly() {
        YearMonth now = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        for (YearMonth month : due(records.partitionedMonths(), now)) {
            Outcome outcome = archive(month, now);
            log.info("Archival of {}: {}", month, outcome);
        }
    }

    /** Partitioned months older than the hot window, oldest first. */
    public static List<YearMonth> due(List<YearMonth> partitioned, YearMonth now) {
        YearMonth oldestHot = now.minusMonths(HOT_MONTHS - 1);
        return partitioned.stream().filter(m -> m.isBefore(oldestHot)).sorted().toList();
    }

    public Outcome archive(YearMonth month, YearMonth now) {
        if (!due(List.of(month), now).contains(month)) {
            return Outcome.NOT_DUE;
        }
        LocalDate from = month.atDay(1);
        LocalDate to = month.plusMonths(1).atDay(1);
        List<UUID> organizations = records.organizationsIn(month);
        for (UUID org : organizations) {
            Long active = tenantScope.call(org, () -> tx.execute(s -> caseLinks.activeCasesLinkedTo(org, from, to)));
            if (active != null && active > 0) {
                log.warn("Not archiving {}: organization {} has {} active case(s) linked to it", month, org, active);
                return Outcome.WAITING_FOR_ACTIVE_CASES;
            }
        }
        for (UUID org : organizations) {
            tenantScope.run(org, () -> tx.executeWithoutResult(s -> archiveOrganization(org, month, from, to)));
        }
        records.dropPartition(month);
        log.info("Dropped partition for {} after archiving {} organization(s)", month, organizations.size());
        return Outcome.ARCHIVED;
    }

    private void archiveOrganization(UUID org, YearMonth month, LocalDate from, LocalDate to) {
        if (alreadyArchived(org, from)) {
            return; // an earlier run got this far; continue with the next organization
        }
        String prefix = org + "/archive/" + month + "/";

        RecordArchive.Figures expected = records.figures(org, month);
        ArchivedFile recordFile = records.export(org, month, prefix + "transaction_record.csv.gz");
        verify(recordFile, expected.rows(), expected.amountSum());

        Object[] matchArgs = {org, from, to, from, to};
        long matches = count("SELECT count(*)" + MATCH_RANGE, matchArgs);
        ArchivedFile matchFile = csv.export(
                prefix + "reconciliation_match.csv.gz", "SELECT m.*" + MATCH_RANGE + " ORDER BY m.id", matchArgs, null);
        verify(matchFile, matches, null);

        long links = caseLinks.countLinks(org, from, to);
        ArchivedFile linkFile = caseLinks.exportLinks(org, from, to, prefix + "exception_case_record.csv.gz");
        verify(linkFile, links, null);

        manifest(org, from, "transaction_record", recordFile);
        manifest(org, from, "reconciliation_match", matchFile);
        manifest(org, from, "exception_case_record", linkFile);

        int deletedLinks = caseLinks.deleteLinks(org, from, to);
        int deletedMatches = jdbc.update("DELETE" + MATCH_RANGE, matchArgs);
        audit.record(AuditEntry.of(AuditAction.PARTITION_ARCHIVED, AuditEntityType.ORGANIZATION, org)
                .organizationId(org)
                .with("month", month.toString())
                .with("records", recordFile.rows())
                .with("amountSum", recordFile.amountSum())
                .with("matches", deletedMatches)
                .with("caseLinks", deletedLinks)
                .with("objectPrefix", prefix));
        log.info(
                "Archived {} for organization {}: {} records, {} matches, {} case links",
                month,
                org,
                recordFile.rows(),
                deletedMatches,
                deletedLinks);
    }

    /** The file read back from storage must hold exactly what the database held. */
    private static void verify(ArchivedFile file, long rows, @Nullable BigDecimal amountSum) {
        boolean amountsMatch = amountSum == null
                || (file.amountSum() != null && file.amountSum().compareTo(amountSum) == 0);
        if (file.rows() != rows || !amountsMatch) {
            throw new IllegalStateException("Archive " + file.key() + " holds " + file.rows() + " rows / "
                    + file.amountSum() + ", the database " + rows + " / " + amountSum + ": nothing deleted");
        }
    }

    private boolean alreadyArchived(UUID org, LocalDate month) {
        return count(
                        "SELECT count(*) FROM archive_manifest WHERE organization_id = ? AND month = ?"
                                + " AND table_name = 'transaction_record'",
                        new Object[] {org, month})
                > 0;
    }

    private void manifest(UUID org, LocalDate month, String table, ArchivedFile file) {
        jdbc.update(
                "INSERT INTO archive_manifest (id, organization_id, month, table_name, object_key, row_count,"
                        + " amount_sum, content_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Ids.newId(),
                org,
                month,
                table,
                file.key(),
                file.rows(),
                file.amountSum(),
                file.sha256());
    }

    private long count(String sql, Object[] args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /** For tests and the runbook: the manifest rows of one organization, as stored. */
    public List<Map<String, Object>> manifest(UUID org) {
        List<Map<String, Object>> rows = new ArrayList<>();
        tenantScope.run(
                org,
                () -> tx.executeWithoutResult(s -> rows.addAll(jdbc.queryForList(
                        "SELECT month, table_name, object_key, row_count, amount_sum, content_sha256 FROM archive_manifest"
                                + " WHERE organization_id = ? ORDER BY table_name",
                        org))));
        return rows;
    }
}
