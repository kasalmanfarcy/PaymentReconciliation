package com.reconflow.paymentreconflow.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.id.Ids;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * M10.6: canaries. Distinctive values go into the data (a reference, an email, a note, a file name)
 * and the main flows run with application logging at DEBUG; the captured output must contain none
 * of them. It fails the first time someone adds {@code log.debug("row {}", rawRow)}, which no code
 * review reliably catches. Logging is raised programmatically (not with a property) so this test
 * shares the suite's application context.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogLeakIT extends AbstractIntegrationTest {

    static final String CANARY_REF = "CANARYREF9876";
    static final String CANARY_EMAIL_PART = "canary.user";
    static final String CANARY_DOMAIN = "@leak.test";
    static final String CANARY_NOTE = "NOTECANARY4321";

    @Autowired
    JobRunner jobRunner;

    @Autowired
    LoggingSystem loggingSystem;

    @BeforeEach
    void debugLogging() {
        loggingSystem.setLogLevel("com.reconflow", LogLevel.DEBUG);
    }

    @AfterEach
    void restoreLogging() {
        loggingSystem.setLogLevel("com.reconflow", null);
    }

    @Test
    void sensitiveValuesNeverReachTheLogs(CapturedOutput output) throws Exception {
        UUID org = data().newOrganization();
        UUID uploader = member(org, Role.UPLOADER);
        UUID investigator = member(org, Role.INVESTIGATOR);
        UUID viewer = member(org, Role.VIEWER);

        // Ledger with the canary reference and two rows the validator rejects (the raw row is stored
        // for the error report, never logged); the gateway disagrees on the amount.
        upload(
                org,
                uploader,
                "ledger-" + CANARY_REF + ".csv",
                """
                reference,amount,currency,status,transaction_at,transaction_type
                %s,100.00,AED,SUCCESS,2026-10-01T09:15:00Z,PAYMENT
                %s-BAD,not-a-number,AED,SUCCESS,2026-10-01T09:15:00Z,PAYMENT
                %s-WORSE,1.00,XXXX,SUCCESS,2026-10-01T09:15:00Z,PAYMENT
                """.formatted(CANARY_REF, CANARY_REF, CANARY_REF),
                "INTERNAL_LEDGER");
        upload(org, uploader, "gateway.csv", """
                merchant_reference,amount,currency,status,transaction_at,gateway_transaction_id
                %s,95.00,AED,captured,2026-10-01T09:20:00Z,gw_canary
                """.formatted(CANARY_REF), "GATEWAY");
        jobRunner.runAllQueued(List.of(org));

        MockHttpServletResponse run = mockMvc.perform(post("/api/v1/reconciliation-runs")
                        .with(as(investigator))
                        .with(org(org))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("periodStart", "2026-10-01", "periodEnd", "2026-10-01", "currency", "AED"))))
                .andReturn()
                .getResponse();
        assertThat(run.getStatus()).as(run.getContentAsString()).isEqualTo(202);
        UUID runId = UUID.fromString(
                jsonMapper.readTree(run.getContentAsString()).get("id").asString());
        jobRunner.runAllQueued(List.of(org));

        UUID caseId = jdbc.queryForObject(
                "SELECT id FROM exception_case WHERE organization_id = ? AND type = 'AMOUNT_MISMATCH'",
                UUID.class,
                org);
        MockHttpServletResponse resolved = mockMvc.perform(post("/api/v1/exceptions/" + caseId + "/resolve")
                        .with(as(investigator))
                        .with(org(org))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of(
                                "resolutionCode", "OTHER", "note", "Checked " + CANARY_REF + " " + CANARY_NOTE))))
                .andReturn()
                .getResponse();
        assertThat(resolved.getStatus()).as(resolved.getContentAsString()).isEqualTo(200);

        MvcResult export = mockMvc.perform(get("/api/v1/reports/exceptions/export?runId=" + runId)
                        .with(as(viewer))
                        .with(org(org)))
                .andReturn();
        if (export.getRequest().isAsyncStarted()) {
            mockMvc.perform(asyncDispatch(export));
        }

        // An expected constraint violation (the duplicate-upload race), translated before logging...
        byte[] same = ("reference,amount,currency,status,transaction_at,transaction_type\n" + CANARY_REF
                        + "-DUP,1.00,AED,SUCCESS,2026-10-02T09:15:00Z,PAYMENT\n")
                .getBytes(StandardCharsets.UTF_8);
        List<CompletableFuture<Integer>> racing = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            racing.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return uploadBytes(org, uploader, "dup-" + CANARY_REF + ".csv", same, "INTERNAL_LEDGER")
                            .getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        assertThat(racing.stream().map(CompletableFuture::join)).contains(202).allMatch(s -> s == 202 || s == 409);
        // ...and an unexpected one: its message, which logs would carry, has no values in it
        // (pgjdbc's logServerErrorDetail=false).
        Throwable unexpected = catchThrowable(() -> appJdbc.update(
                "INSERT INTO app_user (id, email, display_name) VALUES (?, ?, 'x'), (?, ?, 'x')",
                Ids.newId(),
                CANARY_EMAIL_PART + ".dup" + CANARY_DOMAIN,
                Ids.newId(),
                CANARY_EMAIL_PART + ".dup" + CANARY_DOMAIN));
        assertThat(unexpected).isNotNull();
        for (Throwable t = unexpected; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(CANARY_EMAIL_PART);
        }

        String logs = output.getAll();
        assertThat(logs).as("the flows above did log").contains(runId.toString());
        assertThat(logs)
                .doesNotContain(CANARY_REF)
                .doesNotContain(CANARY_NOTE)
                .doesNotContain(CANARY_EMAIL_PART)
                .doesNotContain(CANARY_DOMAIN)
                .doesNotContain("Bearer ey")
                .doesNotContainPattern("(?i)password\\s*[=:]\\s*\\S")
                .doesNotContain("not-a-number");
    }

    private UUID member(UUID org, Role role) {
        UUID id = Ids.newId();
        jdbc.update(
                "INSERT INTO app_user (id, email, display_name, password_hash, must_change_password)"
                        + " VALUES (?, ?, ?, 'unused', false)",
                id,
                CANARY_EMAIL_PART + "+" + id + CANARY_DOMAIN,
                "Canary " + CANARY_NOTE);
        data().addMembership(id, org, role);
        return id;
    }

    private void upload(UUID org, UUID user, String name, String csv, String sourceType) throws Exception {
        MockHttpServletResponse res = uploadBytes(org, user, name, csv.getBytes(StandardCharsets.UTF_8), sourceType);
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
    }

    private MockHttpServletResponse uploadBytes(UUID org, UUID user, String name, byte[] content, String sourceType)
            throws Exception {
        return mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", name, "text/csv", content))
                        .param("sourceType", sourceType)
                        .with(as(user))
                        .with(org(org)))
                .andReturn()
                .getResponse();
    }
}
