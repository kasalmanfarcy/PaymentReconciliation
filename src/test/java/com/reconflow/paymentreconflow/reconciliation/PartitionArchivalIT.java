package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.ledger.internal.PartitionManager;
import com.reconflow.paymentreconflow.reconciliation.archive.PartitionArchival;
import com.reconflow.paymentreconflow.reconciliation.archive.PartitionArchival.Outcome;
import com.reconflow.paymentreconflow.shared.storage.FileStorage;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M9.16: a month older than the 13-month hot window leaves PostgreSQL for verified files in object
 * storage, then its partition is dropped; a month with an open case waits. Uses months no other
 * test touches (2025-01, 2025-02), whose partitions the test creates.
 */
class PartitionArchivalIT extends AbstractIntegrationTest {

    static final YearMonth NOW = YearMonth.of(2026, 11);

    @Autowired
    PartitionArchival archival;

    @Autowired
    PartitionManager partitions;

    @Autowired
    RunDriver driver;

    @Autowired
    FileStorage storage;

    @Test
    void onlyMonthsOlderThanThirteenAreDue() {
        assertThat(PartitionArchival.due(
                        List.of(YearMonth.of(2025, 9), YearMonth.of(2025, 10), YearMonth.of(2025, 11), NOW), NOW))
                .containsExactly(YearMonth.of(2025, 9), YearMonth.of(2025, 10));
        assertThat(archival.archive(YearMonth.of(2025, 11), NOW)).isEqualTo(Outcome.NOT_DUE);
    }

    @Test
    void anOldMonthIsExportedVerifiedAndDroppedForEveryOrganization() throws Exception {
        YearMonth month = YearMonth.of(2025, 1);
        partitions.ensure(List.of(month));
        LocalDate d = month.atDay(10);
        ReconFixture a = new ReconFixture(jdbc);
        ReconFixture b = new ReconFixture(jdbc);
        for (ReconFixture f : List.of(a, b)) {
            f.ledger("AR-1", "100.00", "AED", d);
            f.gateway("AR-1", "100.00", "AED", d);
            f.ledger("AR-2", "55.10", "AED", d.plusDays(1));
            f.gateway("AR-2", "50.00", "AED", d.plusDays(1));
            f.ledger("AR-3", "10.00", "AED", d);
            driver.run(f, d, d.plusDays(1), "AED");
            resolveAll(f.org, f.user);
        }
        BigDecimalPair before = totals(a.org, month);

        assertThat(archival.archive(month, NOW)).isEqualTo(Outcome.ARCHIVED);

        assertThat(partitions.exists(month)).as("partition dropped").isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM transaction_record WHERE organization_id IN (?, ?)",
                        Integer.class,
                        a.org,
                        b.org))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM reconciliation_match WHERE organization_id IN (?, ?)",
                        Integer.class,
                        a.org,
                        b.org))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM exception_case WHERE organization_id = ?", Integer.class, a.org))
                .as("cases and their history stay; only their record links were archived")
                .isEqualTo(2);

        List<Map<String, Object>> manifest = archival.manifest(a.org);
        assertThat(manifest)
                .extracting(m -> m.get("table_name"))
                .containsExactly("exception_case_record", "reconciliation_match", "transaction_record");
        Map<String, Object> recordsRow = manifest.get(2);
        assertThat(recordsRow.get("row_count")).isEqualTo(5L);
        assertThat(new java.math.BigDecimal(recordsRow.get("amount_sum").toString()))
                .isEqualByComparingTo(before.amount);
        assertThat(manifest.get(1).get("row_count")).isEqualTo(1L);
        assertThat(manifest.get(0).get("row_count")).isEqualTo(3L); // mismatch: 2 links, missing: 1

        List<CSVRecord> archived = read(recordsRow.get("object_key").toString());
        assertThat(archived)
                .hasSize(5)
                .allSatisfy(r -> assertThat(r.get("organization_id")).isEqualTo(a.org.toString()));
        assertThat(archived).extracting(r -> r.get("normalized_reference")).contains("AR-1", "AR-2", "AR-3");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_event WHERE organization_id = ? AND action = 'PARTITION_ARCHIVED'",
                        Integer.class,
                        a.org))
                .isEqualTo(1);
    }

    @Test
    void aMonthWithAnOpenCaseWaitsAndNothingChanges() {
        YearMonth month = YearMonth.of(2025, 2);
        partitions.ensure(List.of(month));
        LocalDate d = month.atDay(3);
        ReconFixture f = new ReconFixture(jdbc);
        f.ledger("AW-1", "10.00", "AED", d); // missing in the gateway: an OPEN case
        driver.run(f, d, d, "AED");

        assertThat(archival.archive(month, NOW)).isEqualTo(Outcome.WAITING_FOR_ACTIVE_CASES);

        assertThat(partitions.exists(month)).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM transaction_record WHERE organization_id = ?", Integer.class, f.org))
                .isEqualTo(1);
        assertThat(archival.manifest(f.org)).isEmpty();
    }

    private record BigDecimalPair(long rows, java.math.BigDecimal amount) {}

    private BigDecimalPair totals(UUID org, YearMonth month) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT count(*) AS n, sum(amount) AS total FROM transaction_record WHERE organization_id = ?"
                        + " AND transaction_date >= ? AND transaction_date < ?",
                org,
                month.atDay(1),
                month.plusMonths(1).atDay(1));
        return new BigDecimalPair(((Number) row.get("n")).longValue(), (java.math.BigDecimal) row.get("total"));
    }

    private void resolveAll(UUID org, UUID user) {
        jdbc.update(
                "UPDATE exception_case SET status = 'RESOLVED', resolution_code = 'TIMING_DIFFERENCE',"
                        + " resolved_by = ?, resolved_at = now() WHERE organization_id = ?",
                user,
                org);
    }

    private List<CSVRecord> read(String key) throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        storage.copyTo(key, gz);
        try (Reader in = new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(gz.toByteArray())), StandardCharsets.UTF_8)) {
            return CSVFormat.RFC4180
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .get()
                    .parse(in)
                    .getRecords();
        }
    }
}
