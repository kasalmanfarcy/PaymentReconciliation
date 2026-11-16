package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.shared.context.Role;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * M11.9: the demo dataset produces exactly docs/samples/demo/expected.json, through the same API calls
 * scripts/demo.sh makes, and a second demo against the same organization gives the same summary. The
 * script asserts these numbers against a running stack; this test keeps them true on every build.
 */
class DemoDatasetIT extends AbstractRunApiIT {

    static final Path DEMO = Path.of("docs/samples/demo");

    @Test
    void theDemoProducesExpectedJsonAndRunsTwice() throws Exception {
        JsonNode expected = jsonMapper.readTree(Files.readString(DEMO.resolve("expected.json")));
        var f = new ReconFixture(jdbc);
        UUID uploader = data().newMember(f.org, Role.UPLOADER).id();

        JsonNode ledger = upload(f, uploader, "ledger.csv", "INTERNAL_LEDGER", 202);
        JsonNode gateway = upload(f, uploader, "gateway.csv", "GATEWAY", 202);
        jobRunner.runAllQueued(List.of(f.org));
        assertImport(f, ledger.get("id").asString(), expected.at("/imports/ledger"));
        assertImport(f, gateway.get("id").asString(), expected.at("/imports/gateway"));

        JsonNode run = expected.get("run");
        UUID first = runToCompletion(
                f,
                run.get("periodStart").asString(),
                run.get("periodEnd").asString(),
                run.get("currency").asString());
        assertThat(summary(f, first)).isEqualTo(expected.get("summary"));
        assertThat(openTypes(f, first)).isEqualTo(counts(expected.get("openExceptionTypes")));

        // The demo resolves the large amount mismatch...
        String case302 = caseId(f, first, "DEMO-302");
        MockHttpServletResponse resolved = mockMvc.perform(post("/api/v1/exceptions/" + case302 + "/resolve")
                        .with(as(f.user))
                        .with(org(f.org))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionCode\":\"GATEWAY_FEE\"}"))
                .andReturn()
                .getResponse();
        assertThat(resolved.getStatus()).as(resolved.getContentAsString()).isEqualTo(200);

        // ...and a second demo: the same files are duplicates (409 with the existing batch), and a new
        // run gives the same summary.
        assertThat(upload(f, uploader, "ledger.csv", "INTERNAL_LEDGER", 409)
                        .get("existingBatchId")
                        .asString())
                .isEqualTo(ledger.get("id").asString());
        UUID second = runToCompletion(
                f,
                run.get("periodStart").asString(),
                run.get("periodEnd").asString(),
                run.get("currency").asString());
        assertThat(summary(f, second)).isEqualTo(expected.get("summary"));
        // The resolved case stays resolved; the re-run finds the discrepancy again and opens a new case.
        assertThat(caseId(f, second, "DEMO-302")).isNotEqualTo(case302);
    }

    private JsonNode upload(ReconFixture f, UUID uploader, String file, String sourceType, int status)
            throws Exception {
        MockHttpServletResponse res = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", file, "text/csv", Files.readAllBytes(DEMO.resolve(file))))
                        .param("sourceType", sourceType)
                        .with(as(uploader))
                        .with(org(f.org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(status);
        return jsonMapper.readTree(res.getContentAsString());
    }

    private void assertImport(ReconFixture f, String id, JsonNode expected) throws Exception {
        JsonNode batch = getJson(f, "/api/v1/imports/" + id);
        expected.properties()
                .forEach(e -> assertThat(batch.get(e.getKey())).as(e.getKey()).isEqualTo(e.getValue()));
    }

    private JsonNode summary(ReconFixture f, UUID run) throws Exception {
        return getJson(f, RUNS + "/" + run).get("summary");
    }

    private Map<String, Integer> openTypes(ReconFixture f, UUID run) throws Exception {
        Map<String, Integer> types = new TreeMap<>();
        getJson(f, "/api/v1/exceptions?runId=" + run + "&status=OPEN&size=200")
                .get("content")
                .forEach(c -> types.merge(c.get("type").asString(), 1, Integer::sum));
        return types;
    }

    private String caseId(ReconFixture f, UUID run, String reference) throws Exception {
        for (JsonNode c : getJson(f, "/api/v1/exceptions?runId=" + run + "&status=OPEN&size=200")
                .get("content")) {
            if (reference.equals(c.get("reference").asString())) {
                return c.get("id").asString();
            }
        }
        throw new AssertionError("no open case for " + reference + " in run " + run);
    }

    private static Map<String, Integer> counts(JsonNode node) {
        Map<String, Integer> counts = new TreeMap<>();
        node.properties().forEach(e -> counts.put(e.getKey(), e.getValue().asInt()));
        return counts;
    }

    private JsonNode getJson(ReconFixture f, String path) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get(path).with(as(f.user)).with(org(f.org)))
                .andReturn()
                .getResponse();
        assertThat(res.getStatus()).as(path + ": " + res.getContentAsString()).isEqualTo(200);
        return jsonMapper.readTree(res.getContentAsString());
    }
}
