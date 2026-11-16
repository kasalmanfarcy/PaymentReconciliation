package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/**
 * M5.26: uploads refused synchronously with a clear ProblemDetail (requirements.md §7.4,
 * api-design.md §3), and nothing created: no batch, no job, no stored object, no audit event.
 */
class ImportRejectionIT extends AbstractImportIT {

    @Test
    void wrongHeadersListTheMissingColumns() throws Exception {
        Tenant t = newTenant();

        MockHttpServletResponse res =
                upload(t, "gateway-wrong-headers.csv", sample("gateway-wrong-headers.csv"), "GATEWAY");

        Map<String, Object> problem = assertProblem(t, res, 400, "missing-columns");
        assertThat(problem).containsEntry("missingColumns", List.of("merchant_reference", "amount", "currency"));
    }

    @Test
    void aLedgerWithNeitherDateColumnIsMissingOneOfThem() throws Exception {
        Tenant t = newTenant();

        MockHttpServletResponse res =
                upload(t, "l.csv", utf8("reference,amount,currency,status\nPAY-1,1,AED,SUCCESS\n"), "INTERNAL_LEDGER");

        assertThat(assertProblem(t, res, 400, "missing-columns"))
                .containsEntry("missingColumns", List.of("transaction_at or transaction_date"));
    }

    @Test
    void repeatedHeaderNamesAreRejected() throws Exception {
        Tenant t = newTenant();

        MockHttpServletResponse res = upload(
                t,
                "l.csv",
                utf8("reference,amount,AMOUNT ,currency,status,transaction_date\nP,1,1,AED,SUCCESS,2026-10-01\n"),
                "INTERNAL_LEDGER");

        assertThat(assertProblem(t, res, 400, "duplicate-columns"))
                .containsEntry("duplicateColumns", List.of("amount"));
    }

    @Test
    void aZeroByteFileIsEmpty() throws Exception {
        Tenant t = newTenant();
        assertProblem(t, upload(t, "empty.csv", new byte[0], "INTERNAL_LEDGER"), 400, "empty-file");
    }

    @Test
    void aFileOfBlankLinesIsEmpty() throws Exception {
        Tenant t = newTenant();
        assertProblem(t, upload(t, "blank.csv", utf8("\n\n\r\n"), "INTERNAL_LEDGER"), 400, "empty-file");
    }

    @Test
    void aHeaderWithoutDataRowsIsEmpty() throws Exception {
        // requirements.md §7.4 folds "no data row" into empty-file (the plan had a separate no-data-rows).
        Tenant t = newTenant();
        assertProblem(
                t,
                upload(t, "ledger-header-only.csv", sample("ledger-header-only.csv"), "INTERNAL_LEDGER"),
                400,
                "empty-file");
    }

    @Test
    void aMissingFilePartIsEmpty() throws Exception {
        Tenant t = newTenant();
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/v1/imports")
                        .param("sourceType", "GATEWAY")
                        .with(as(t.uploader()))
                        .with(org(t.org())))
                .andReturn()
                .getResponse();
        assertProblem(t, res, 400, "empty-file");
    }

    @Test
    void aFileOverFiftyMegabytesIsTooLarge() throws Exception {
        Tenant t = newTenant();
        byte[] big = new byte[51 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');

        assertProblem(t, upload(t, "big.csv", big, "GATEWAY"), 413, "file-too-large");
    }

    @Test
    void aSpreadsheetIsAnUnsupportedMediaType() throws Exception {
        Tenant t = newTenant();
        MockMultipartFile xlsx = new MockMultipartFile(
                "file", "book.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {
                    1, 2
                });

        assertProblem(t, upload(t, xlsx, "GATEWAY"), 415, "unsupported-media-type");
    }

    @Test
    void aCsvNameWithANonCsvContentTypeIsAnUnsupportedMediaType() throws Exception {
        Tenant t = newTenant();
        MockMultipartFile png = new MockMultipartFile("file", "photo.csv", "image/png", sample("gateway-basic.csv"));

        assertProblem(t, upload(t, png, "GATEWAY"), 415, "unsupported-media-type");
    }

    @Test
    void theContentTypesWindowsAndBrowsersSendForCsvAreAccepted() throws Exception {
        Tenant t = newTenant();
        int n = 0;
        for (String type : List.of(
                "text/plain", "application/vnd.ms-excel", "application/octet-stream", "text/csv; charset=utf-8")) {
            byte[] file = utf8(
                    "reference,amount,currency,status,transaction_date\nPAY-" + n++ + ",1,AED,SUCCESS,2026-10-01\n");
            assertThat(upload(t, new MockMultipartFile("file", "x.CSV", type, file), "INTERNAL_LEDGER")
                            .getStatus())
                    .as(type)
                    .isEqualTo(202);
        }
    }

    @Test
    void aRequestThatIsNotMultipartIsAnUnsupportedMediaType() throws Exception {
        Tenant t = newTenant();
        MockHttpServletResponse res = mockMvc.perform(post("/api/v1/imports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(as(t.uploader()))
                        .with(org(t.org())))
                .andReturn()
                .getResponse();

        assertProblem(t, res, 415, "unsupported-media-type");
    }

    @Test
    void latin1HeaderBytesAreAnInvalidEncoding() throws Exception {
        Tenant t = newTenant();
        // "référence" in ISO-8859-1: 0xE9 alone is not valid UTF-8.
        byte[] latin1 = "réference,amount\nPAY-1,1\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        assertProblem(t, upload(t, "latin1.csv", latin1, "INTERNAL_LEDGER"), 400, "invalid-encoding");
    }

    @Test
    void aMissingOrUnknownSourceTypeIsAValidationFailure() throws Exception {
        Tenant t = newTenant();
        MockHttpServletResponse missing = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", "l.csv", "text/csv", sample("ledger-basic.csv")))
                        .with(as(t.uploader()))
                        .with(org(t.org())))
                .andReturn()
                .getResponse();
        assertProblem(t, missing, 400, "validation-failed");
        assertProblem(t, upload(t, "l.csv", sample("ledger-basic.csv"), "BANK"), 400, "validation-failed");
    }

    /** The full ProblemDetail shape, and nothing created for the organization. */
    private Map<String, Object> assertProblem(Tenant t, MockHttpServletResponse res, int status, String code)
            throws Exception {
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        assertThat(res.getContentType()).isEqualTo("application/problem+json");
        Map<String, Object> problem = json(res);
        assertThat(problem)
                .containsEntry("type", "https://reconflow.dev/problems/" + code)
                .containsEntry("status", status)
                .containsEntry("code", code)
                .containsEntry("requestId", res.getHeader("X-Request-Id"))
                .containsKeys("title", "detail");
        assertThat(problem.get("detail").toString()).doesNotContain("Exception");
        assertThat(count("SELECT count(*) FROM import_batch WHERE organization_id = ?", t.org()))
                .isZero();
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ?", t.org()))
                .isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE organization_id = ?", t.org()))
                .isZero();
        long[] objects = {0};
        storage.forEach(t.org() + "/", o -> objects[0]++);
        assertThat(objects[0]).as("stored objects").isZero();
        return problem;
    }
}
