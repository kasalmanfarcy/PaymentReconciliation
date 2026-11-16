package com.reconflow.paymentreconflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M10.2 end to end, through the real paths an attacker would use: formula-like references arrive in
 * an uploaded gateway file, go through the importer (which must store them unchanged, M5.12), the
 * engine and the exception workflow, and come out of the export as inert text, while the amount
 * difference beside them stays a number a spreadsheet can sum. The escaping itself is
 * {@code CsvCell} (M8.4, unit-tested in {@code CsvCellTest}).
 */
class ExportInjectionIT extends AbstractIntegrationTest {

    @Autowired
    JobRunner jobRunner;

    @Test
    void formulaReferencesFromAnUploadedFileAreInertInTheExportAndAmountsStayNumeric() throws Exception {
        UUID org = data().newOrganization();
        UUID uploader = data().newMember(org, Role.UPLOADER).id();
        UUID viewer = data().newMember(org, Role.VIEWER).id();
        // =1+1 is in both files with different amounts: an AMOUNT_MISMATCH with a negative
        // difference. The others are gateway-only (MISSING_IN_LEDGER): one per trigger character.
        String ledger = """
                reference,amount,currency,status,transaction_at,transaction_type
                =1+1,100.00,AED,SUCCESS,2026-10-01T09:15:00Z,PAYMENT
                """;
        String gateway = """
                merchant_reference,amount,currency,status,transaction_at,gateway_transaction_id
                =1+1,95.00,AED,captured,2026-10-01T09:20:00Z,gw_1
                +SUM(1),10.00,AED,captured,2026-10-01T09:21:00Z,gw_2
                -2+3,11.00,AED,captured,2026-10-01T09:22:00Z,gw_3
                @SUM(1),12.00,AED,captured,2026-10-01T09:23:00Z,gw_4
                """;
        upload(org, uploader, "ledger.csv", ledger, "INTERNAL_LEDGER");
        upload(org, uploader, "gateway.csv", gateway, "GATEWAY");
        jobRunner.runAllQueued(List.of(org));
        assertThat(jdbc.queryForList(
                        "SELECT raw_reference FROM transaction_record WHERE organization_id = ? ORDER BY 1",
                        String.class,
                        org))
                .as("stored exactly as uploaded: escaping is an output concern")
                .contains("=1+1", "+SUM(1)", "-2+3", "@SUM(1)");

        MockHttpServletResponse created = mockMvc.perform(post("/api/v1/reconciliation-runs")
                        .with(as(uploader))
                        .with(org(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("periodStart", "2026-10-01", "periodEnd", "2026-10-01", "currency", "AED"))))
                .andReturn()
                .getResponse();
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(202);
        UUID run = UUID.fromString(
                jsonMapper.readTree(created.getContentAsString()).get("id").asString());
        jobRunner.runAllQueued(List.of(org));

        MvcResult started = mockMvc.perform(get("/api/v1/reports/exceptions/export?runId=" + run)
                        .with(as(viewer))
                        .with(org(org)))
                .andReturn();
        MockHttpServletResponse res = started.getRequest().isAsyncStarted()
                ? mockMvc.perform(asyncDispatch(started)).andReturn().getResponse()
                : started.getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).startsWith("text/csv");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        List<CSVRecord> rows = parse(res.getContentAsString(StandardCharsets.UTF_8));

        CSVRecord mismatch = byType(rows, "AMOUNT_MISMATCH");
        assertThat(mismatch.get("ledger_references")).isEqualTo("'=1+1");
        assertThat(mismatch.get("gateway_references")).isEqualTo("'=1+1");
        assertThat(mismatch.get("amount_difference")).isEqualTo("-5.0000");
        assertThat(new BigDecimal(mismatch.get("amount_difference")))
                .as("still a number")
                .isEqualByComparingTo("-5");
        assertThat(rows.stream()
                        .filter(r -> r.get("type").equals("MISSING_IN_LEDGER"))
                        .map(r -> r.get("gateway_references")))
                .containsExactlyInAnyOrder("'+SUM(1)", "'-2+3", "'@SUM(1)");
    }

    private void upload(UUID org, UUID uploader, String name, String csv, String sourceType) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .param("sourceType", sourceType)
                        .with(as(uploader))
                        .with(org(org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
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
