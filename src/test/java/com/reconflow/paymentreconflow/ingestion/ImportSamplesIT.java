package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.mock.web.MockHttpServletResponse;
import org.yaml.snakeyaml.Yaml;

/**
 * M5.22: every sample in docs/samples imports with exactly the outcome its README states, as
 * transcribed in docs/samples/expected-imports.yaml. Each file goes into a fresh organization (the
 * README warns that ledger-bom-crlf.csv and ledger-basic.csv in one organization are cross-file
 * duplicates).
 */
class ImportSamplesIT extends AbstractImportIT {

    static final Path EXPECTATIONS = SAMPLES.resolve("expected-imports.yaml");

    /** The normalized columns compared row for row by {@code sameRecordsAs}. */
    static final String NORMALIZED = """
            SELECT source_row_number, normalized_reference, amount::text AS amount, currency, transaction_type,
                   status, transaction_date::text AS transaction_date, transaction_at, is_duplicate_in_source
              FROM transaction_record WHERE batch_id = ? ORDER BY source_row_number""";

    @Test
    void everySampleHasExpectations() throws IOException {
        List<String> csvs;
        try (Stream<Path> files = Files.list(SAMPLES)) {
            csvs = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".csv"))
                    .toList();
        }
        assertThat(expectations().keySet()).containsExactlyInAnyOrderElementsOf(csvs);
    }

    @TestFactory
    Stream<DynamicTest> everySampleImportsAsDocumented() throws IOException {
        return expectations().entrySet().stream()
                .map(e -> DynamicTest.dynamicTest(e.getKey(), () -> check(e.getKey(), e.getValue())));
    }

    @SuppressWarnings("unchecked")
    private void check(String file, Map<String, Object> expected) throws Exception {
        Tenant t = newTenant();
        String sourceType = (String) expected.get("sourceType");
        MockHttpServletResponse res = upload(t, file, sample(file), sourceType);

        if (expected.containsKey("rejected")) {
            Map<String, Object> rejected = (Map<String, Object>) expected.get("rejected");
            assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(rejected.get("status"));
            Map<String, Object> problem = json(res);
            assertThat(problem).containsEntry("code", rejected.get("code"));
            if (rejected.containsKey("missingColumns")) {
                assertThat(problem).containsEntry("missingColumns", rejected.get("missingColumns"));
            }
            assertThat(count("SELECT count(*) FROM import_batch WHERE organization_id = ?", t.org()))
                    .isZero();
            return;
        }

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        UUID batch = UUID.fromString(json(res).get("id").toString());
        runQueue(t);

        Map<String, Object> row = batchRow(batch);
        assertThat(row.get("status")).as("status").isEqualTo(expected.get("status"));
        assertThat(row.get("total_rows")).as("totalRows").isEqualTo(expected.get("totalRows"));
        assertThat(row.get("valid_rows")).as("validRows").isEqualTo(expected.get("validRows"));
        assertThat(row.get("error_rows")).as("errorRows").isEqualTo(expected.get("errorRows"));

        List<Map<String, Object>> errors = jdbc.queryForList(
                "SELECT row_number AS row, column_name AS column, error_code AS code FROM import_row_error"
                        + " WHERE batch_id = ? ORDER BY row_number, column_name NULLS FIRST",
                batch);
        assertThat(errors).as("row errors").isEqualTo(expected.get("errors"));

        assertThat(jdbc.queryForList(
                        "SELECT source_row_number FROM transaction_record WHERE batch_id = ? AND is_duplicate_in_source"
                                + " ORDER BY 1",
                        Integer.class,
                        batch))
                .as("duplicates")
                .isEqualTo(expected.get("duplicates"));

        Map<Integer, Map<String, Object>> records =
                (Map<Integer, Map<String, Object>>) expected.getOrDefault("records", Map.of());
        records.forEach((number, fields) -> {
            Map<String, Object> actual = jdbc.queryForMap("""
                    SELECT raw_reference AS "rawReference", normalized_reference AS "normalizedReference",
                           amount::text AS amount, currency, status, transaction_type AS "transactionType",
                           transaction_date::text AS "transactionDate"
                      FROM transaction_record WHERE batch_id = ? AND source_row_number = ?""", batch, number);
            fields.forEach((field, value) ->
                    assertThat(actual.get(field)).as("row %d %s", number, field).isEqualTo(value));
        });

        if (expected.containsKey("rawValueKeys")) {
            String keys = jdbc.queryForObject(
                    "SELECT string_agg(k, '|') FROM transaction_record, jsonb_object_keys(raw_values) AS k"
                            + " WHERE batch_id = ? AND source_row_number = 1",
                    String.class,
                    batch);
            assertThat(List.of(keys.split("\\|")))
                    .as("raw_values keys")
                    .containsExactlyInAnyOrderElementsOf((List<String>) expected.get("rawValueKeys"));
        }

        if (expected.containsKey("sameRecordsAs")) {
            String other = (String) expected.get("sameRecordsAs");
            Tenant reference = newTenant();
            UUID otherBatch = uploadOk(reference, other, sample(other), sourceType);
            runQueue(reference);
            assertThat(jdbc.queryForList(NORMALIZED, batch))
                    .as("normalized records identical to " + other)
                    .isEqualTo(jdbc.queryForList(NORMALIZED, otherBatch));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> expectations() throws IOException {
        try (Reader reader = Files.newBufferedReader(EXPECTATIONS)) {
            Map<String, Object> raw = new Yaml().load(reader);
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            raw.forEach((file, value) -> out.put(file, (Map<String, Object>) value));
            return out;
        }
    }
}
