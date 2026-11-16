package com.reconflow.paymentreconflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M8.4: {@code GET /api/v1/reports/exceptions/export}. Streamed CSV that a spreadsheet opens
 * correctly (a reference with a comma, a note with a newline, a reference that looks like a
 * formula); the background path above the limit, whose file is byte for byte what the streamed
 * path gives; both audited.
 */
class ExceptionExportIT extends AbstractIntegrationTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @Autowired
    JobRunner jobRunner;

    @Autowired
    AdjustableExportLimit limit;

    /** Three cases: a mismatch with a comma in its reference, a formula-like reference, a missing one. */
    private UUID runWithThreeCases(ReportFixture f) throws Exception {
        f.ledger("PAY,1", "100.00", DAY);
        f.gateway("PAY,1", "95.00", DAY);
        f.ledger("=HYPERLINK(\"http://evil.example\")", "10.00", DAY);
        f.gateway("PAY-3", "7.50", DAY);
        UUID run = f.run(jobRunner, DAY, DAY);
        // Resolve the mismatch with a two-line note, through the API.
        UUID mismatch = jdbc.queryForObject(
                "SELECT id FROM exception_case WHERE organization_id = ? AND type = 'AMOUNT_MISMATCH'",
                UUID.class,
                f.org);
        MockHttpServletResponse resolved = mockMvc.perform(post("/api/v1/exceptions/" + mismatch + "/resolve")
                        .with(as(f.investigator.id()))
                        .with(org(f.org))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("resolutionCode", "OTHER", "note", "Fee of 5.00\nconfirmed by the gateway"))))
                .andReturn()
                .getResponse();
        assertThat(resolved.getStatus()).as(resolved.getContentAsString()).isEqualTo(200);
        return run;
    }

    @Test
    void aSmallRunStreamsAValidCsv() throws Exception {
        var f = new ReportFixture(jdbc);
        UUID run = runWithThreeCases(f);

        MockHttpServletResponse res = export(f, run);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).startsWith("text/csv");
        assertThat(res.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"exceptions-" + run + ".csv\"");
        String body = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿case_id,type,");
        List<CSVRecord> rows = parse(body);
        assertThat(rows).hasSize(3);
        CSVRecord mismatch = byType(rows, "AMOUNT_MISMATCH");
        assertThat(mismatch.get("ledger_references")).isEqualTo("PAY,1"); // the comma survived, quoted
        assertThat(mismatch.get("ledger_amounts")).isEqualTo("100.0000");
        assertThat(mismatch.get("gateway_amounts")).isEqualTo("95.0000");
        assertThat(mismatch.get("amount_difference")).isEqualTo("-5.0000"); // a number, not escaped
        assertThat(mismatch.get("status")).isEqualTo("RESOLVED");
        assertThat(mismatch.get("resolution_code")).isEqualTo("OTHER");
        assertThat(mismatch.get("resolution_note")).isEqualTo("Fee of 5.00\nconfirmed by the gateway");
        assertThat(mismatch.get("resolved_by")).isNotEmpty();
        CSVRecord formula = byType(rows, "MISSING_IN_GATEWAY");
        // CSV injection: the spreadsheet shows this as text instead of running it.
        assertThat(formula.get("ledger_references")).isEqualTo("'=HYPERLINK(\"http://evil.example\")");
        assertThat(formula.get("gateway_references")).isEmpty();
        assertThat(byType(rows, "MISSING_IN_LEDGER").get("gateway_amounts")).isEqualTo("7.5000");
        // Audited, with the row count, as the person who asked.
        assertThat(jdbc.queryForMap(
                        "SELECT actor_id, payload->>'mode' AS mode, payload->>'rowCount' AS rows FROM audit_event"
                                + " WHERE organization_id = ? AND action = 'EXCEPTIONS_EXPORTED'",
                        f.org))
                .containsEntry("actor_id", f.viewer.id())
                .containsEntry("mode", "SYNC")
                .containsEntry("rows", "3");
    }

    @Test
    void aLargeRunGoesThroughAJobAndGivesTheSameFile() throws Exception {
        var f = new ReportFixture(jdbc);
        UUID run = runWithThreeCases(f);
        limit.set(f.org, 2); // three exceptions: over the limit
        try {
            MockHttpServletResponse accepted = export(f, run);

            assertThat(accepted.getStatus()).isEqualTo(202);
            assertThat(accepted.getHeader("Retry-After")).isEqualTo("2");
            Map<?, ?> queued = jsonMapper.readValue(accepted.getContentAsString(), Map.class);
            UUID exportId = UUID.fromString(queued.get("id").toString());
            assertThat(accepted.getHeader("Location")).isEqualTo("/api/v1/reports/exports/" + exportId);
            assertThat(queued.get("status")).isEqualTo("QUEUED");
            assertThat(queued.get("runId")).isEqualTo(run.toString());
            assertThat(queued.get("fileUrl")).isNull();

            MockHttpServletResponse early = call(f, "/api/v1/reports/exports/" + exportId + "/file");
            assertThat(early.getStatus()).isEqualTo(409);
            assertThat(early.getContentAsString()).contains("\"code\":\"export-not-ready\"");

            jobRunner.runAllQueued(List.of(f.org));

            MockHttpServletResponse status = call(f, "/api/v1/reports/exports/" + exportId);
            assertThat(status.getStatus()).isEqualTo(200);
            assertThat(status.getHeader("Retry-After")).isNull();
            assertThat(jsonMapper.readValue(status.getContentAsString(), Map.class))
                    .containsEntry("status", "DONE")
                    .containsEntry("fileUrl", "/api/v1/reports/exports/" + exportId + "/file");

            MockHttpServletResponse file = stream(f, "/api/v1/reports/exports/" + exportId + "/file");
            assertThat(file.getStatus()).isEqualTo(200);
            assertThat(file.getContentType()).startsWith("text/csv");
            assertThat(file.getHeader("Content-Disposition")).startsWith("attachment;");

            limit.reset(f.org);
            MockHttpServletResponse sync = export(f, run);
            assertThat(sync.getStatus()).isEqualTo(200);
            assertThat(file.getContentAsByteArray()).isEqualTo(sync.getContentAsByteArray());
        } finally {
            limit.reset(f.org);
        }
        assertThat(jdbc.queryForList(
                        "SELECT action || ' ' || coalesce(payload->>'mode', '-') || ' ' || (actor_id = ?)"
                                + " FROM audit_event WHERE organization_id = ? AND action IN"
                                + " ('EXPORT_REQUESTED', 'EXCEPTIONS_EXPORTED') ORDER BY created_at, id",
                        String.class,
                        f.viewer.id(),
                        f.org))
                .containsExactly(
                        "EXPORT_REQUESTED - true", "EXCEPTIONS_EXPORTED ASYNC true", "EXCEPTIONS_EXPORTED SYNC true");
    }

    @Test
    void unknownRunsAndExportsAre404() throws Exception {
        var f = new ReportFixture(jdbc);
        UUID run = f.run(jobRunner, DAY, DAY);
        UUID reconcileJob = jdbc.queryForObject(
                "SELECT id FROM job WHERE organization_id = ? AND type = 'RECONCILE'", UUID.class, f.org);

        assertThat(call(f, "/api/v1/reports/exceptions/export?runId=" + UUID.randomUUID())
                        .getStatus())
                .isEqualTo(404);
        assertThat(call(f, "/api/v1/reports/exports/" + UUID.randomUUID()).getStatus())
                .isEqualTo(404);
        // A job of the organization that isn't an export is not an export.
        assertThat(call(f, "/api/v1/reports/exports/" + reconcileJob).getStatus())
                .isEqualTo(404);
        assertThat(call(f, "/api/v1/reports/exports/" + reconcileJob + "/file").getStatus())
                .isEqualTo(404);
        // A run with no exceptions exports just the header row.
        assertThat(parse(export(f, run).getContentAsString(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    /** The export request: synchronous (404, 202) or streamed (200, completed through asyncDispatch). */
    private MockHttpServletResponse export(ReportFixture f, UUID run) throws Exception {
        return stream(f, "/api/v1/reports/exceptions/export?runId=" + run);
    }

    private MockHttpServletResponse stream(ReportFixture f, String path) throws Exception {
        MvcResult started = mockMvc.perform(get(path).with(as(f.viewer.id())).with(org(f.org)))
                .andReturn();
        if (!started.getRequest().isAsyncStarted()) {
            return started.getResponse();
        }
        return mockMvc.perform(asyncDispatch(started)).andReturn().getResponse();
    }

    private MockHttpServletResponse call(ReportFixture f, String path) throws Exception {
        return mockMvc.perform(get(path).with(as(f.viewer.id())).with(org(f.org)))
                .andReturn()
                .getResponse();
    }

    private static List<CSVRecord> parse(String body) throws Exception {
        String withoutBom = body.startsWith("﻿") ? body.substring(1) : body;
        return CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get()
                .parse(new StringReader(withoutBom))
                .getRecords();
    }

    private static CSVRecord byType(List<CSVRecord> rows, String type) {
        return rows.stream().filter(r -> r.get("type").equals(type)).findFirst().orElseThrow();
    }
}
