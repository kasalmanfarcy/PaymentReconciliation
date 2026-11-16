package com.reconflow.paymentreconflow.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.exceptions.ExceptionType;
import com.reconflow.paymentreconflow.exceptions.internal.OpenExceptionsGauge;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/**
 * M9.9: the business and processing metrics move by exactly what an import and a run did, and no
 * metric carries a high-cardinality tag (an organization, batch, run or user id, or a raw URL).
 */
class MetricsIT extends AbstractIntegrationTest {

    /** The only tag keys our metrics may use: each has a small fixed set of values. */
    static final Set<String> ALLOWED_TAG_KEYS = Set.of(
            "source_type",
            "outcome",
            "result",
            "type",
            "operation",
            "application",
            "le"); // le: histogram bucket bounds, fixed

    @Autowired
    MeterRegistry meters;

    @Autowired
    JobRunner jobRunner;

    @Autowired
    OpenExceptionsGauge openExceptions;

    @Test
    void anImportAndARunMoveTheCountersByExactlyWhatTheyDid() throws Exception {
        double validBefore = count("reconflow.import.rows", "source_type", "INTERNAL_LEDGER", "outcome", "valid");
        double rejectedBefore = count("reconflow.import.rows", "source_type", "GATEWAY", "outcome", "rejected");
        long importsBefore = timerCount("reconflow.import.duration", "source_type", "INTERNAL_LEDGER");
        long runsBefore = timerCount("reconflow.reconciliation.duration", "outcome", "completed");
        double matchedBefore = count("reconflow.reconciliation.results", "result", "matched");
        double mismatchBefore = count("reconflow.reconciliation.results", "result", "mismatch");
        double doneBefore = count("reconflow.jobs.completed", "type", "IMPORT", "outcome", "done");
        long waitsBefore = timerCount("reconflow.jobs.queue.wait", "type", "RECONCILE");

        UUID org = data().newOrganization();
        UUID user = data().newMember(org, Role.UPLOADER).id();
        upload(org, user, "ledger-basic.csv", "INTERNAL_LEDGER");
        upload(org, user, "gateway-basic.csv", "GATEWAY");
        jobRunner.runAllQueued(List.of(org));
        mockMvc.perform(post("/api/v1/reconciliation-runs")
                .with(as(user))
                .with(org(org))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2026-10-02\",\"currency\":\"AED\"}"));
        jobRunner.runAllQueued(List.of(org));

        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT valid_rows, error_rows FROM import_batch WHERE organization_id = ? AND source_type = 'INTERNAL_LEDGER'",
                org);
        Map<String, Object> gateway = jdbc.queryForMap(
                "SELECT error_rows FROM import_batch WHERE organization_id = ? AND source_type = 'GATEWAY'", org);
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT matched_count, mismatch_count FROM reconciliation_run WHERE organization_id = ?", org);

        assertThat(count("reconflow.import.rows", "source_type", "INTERNAL_LEDGER", "outcome", "valid") - validBefore)
                .isEqualTo(((Number) ledger.get("valid_rows")).doubleValue());
        assertThat(count("reconflow.import.rows", "source_type", "GATEWAY", "outcome", "rejected") - rejectedBefore)
                .isEqualTo(((Number) gateway.get("error_rows")).doubleValue());
        assertThat(timerCount("reconflow.import.duration", "source_type", "INTERNAL_LEDGER") - importsBefore)
                .isEqualTo(1);
        assertThat(timerCount("reconflow.reconciliation.duration", "outcome", "completed") - runsBefore)
                .isEqualTo(1);
        assertThat(count("reconflow.reconciliation.results", "result", "matched") - matchedBefore)
                .isEqualTo(((Number) run.get("matched_count")).doubleValue())
                .isPositive();
        assertThat(count("reconflow.reconciliation.results", "result", "mismatch") - mismatchBefore)
                .isEqualTo(((Number) run.get("mismatch_count")).doubleValue());
        assertThat(count("reconflow.jobs.completed", "type", "IMPORT", "outcome", "done") - doneBefore)
                .isEqualTo(2);
        assertThat(timerCount("reconflow.jobs.queue.wait", "type", "RECONCILE") - waitsBefore)
                .isEqualTo(1);

        openExceptions.refresh();
        assertThat(openExceptions.value(ExceptionType.AMOUNT_MISMATCH))
                .as("all organizations' open mismatches, this one's included")
                .isGreaterThanOrEqualTo(((Number) run.get("mismatch_count")).longValue());
        assertThat(meters.find("reconflow.exceptions.open").gauges()).hasSize(ExceptionType.values().length);
        assertThat(meters.find("reconflow.jobs.queued").gauges()).hasSize(3);
        assertThat(meters.find("reconflow.partitions.default.rows").gauge()).isNotNull();
    }

    @Test
    void httpMetricsUseTheUriTemplateAndNoMetricHasAHighCardinalityTag() throws Exception {
        UUID org = data().newOrganization();
        UUID user = data().newMember(org, Role.UPLOADER).id();
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/imports/" + unknown).with(as(user)).with(org(org)));

        Set<String> uris = meters.find("http.server.requests").meters().stream()
                .map(m -> m.getId().getTag("uri"))
                .collect(Collectors.toSet());
        assertThat(uris).contains("/api/v1/imports/{id}").noneMatch(u -> u != null && u.contains(unknown.toString()));

        for (Meter m : meters.getMeters()) {
            String name = m.getId().getName();
            if (!name.startsWith("reconflow.")) {
                continue;
            }
            assertThat(m.getId().getTags())
                    .as(name)
                    .allSatisfy(tag -> assertThat(ALLOWED_TAG_KEYS).contains(tag.getKey()))
                    .noneSatisfy(tag -> assertThat(tag.getValue()).matches("[0-9a-f]{8}-[0-9a-f]{4}-.*"));
        }
    }

    private void upload(UUID org, UUID user, String sample, String source) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile(
                                "file", sample, "text/csv", Files.readAllBytes(Path.of("docs/samples", sample))))
                        .param("sourceType", source)
                        .with(as(user))
                        .with(org(org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).isEqualTo(202);
    }

    private double count(String name, String... tags) {
        Counter c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private long timerCount(String name, String... tags) {
        return meters.find(name).tags(tags).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }
}
