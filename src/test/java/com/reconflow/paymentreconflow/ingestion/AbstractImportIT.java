package com.reconflow.paymentreconflow.ingestion;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.shared.jobs.JobRunner;
import com.reconflow.paymentreconflow.shared.storage.FileStorage;
import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Shared steps for import tests: a fresh organization with an uploader, an upload through the real
 * endpoint, and the queue run synchronously for that organization only.
 */
abstract class AbstractImportIT extends AbstractIntegrationTest {

    static final Path SAMPLES = Path.of("docs/samples");

    @Autowired
    protected JobRunner jobRunner;

    @Autowired
    protected FileStorage storage;

    protected record Tenant(UUID org, UUID uploader) {}

    protected Tenant newTenant() {
        UUID org = data().newOrganization();
        return new Tenant(org, data().newMember(org, Role.UPLOADER).id());
    }

    protected static byte[] sample(String name) {
        try {
            return Files.readAllBytes(SAMPLES.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    protected MockHttpServletResponse upload(Tenant t, String fileName, byte[] content, String sourceType)
            throws Exception {
        return upload(t, new MockMultipartFile("file", fileName, "text/csv", content), sourceType);
    }

    protected MockHttpServletResponse upload(Tenant t, MockMultipartFile file, String sourceType) throws Exception {
        return mockMvc.perform(multipart("/api/v1/imports")
                        .file(file)
                        .param("sourceType", sourceType)
                        .with(as(t.uploader()))
                        .with(org(t.org())))
                .andReturn()
                .getResponse();
    }

    /** Uploads and returns the new batch's id; fails the test unless the upload got 202. */
    protected UUID uploadOk(Tenant t, String fileName, byte[] content, String sourceType) throws Exception {
        MockHttpServletResponse res = upload(t, fileName, content, sourceType);
        if (res.getStatus() != 202) {
            throw new AssertionError("expected 202, got " + res.getStatus() + ": " + res.getContentAsString());
        }
        return UUID.fromString(json(res).get("id").toString());
    }

    protected int runQueue(Tenant t) {
        return jobRunner.runAllQueued(List.of(t.org()));
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> json(MockHttpServletResponse res) throws Exception {
        return jsonMapper.readValue(res.getContentAsString(), Map.class);
    }

    protected Map<String, Object> batchRow(UUID batch) {
        return jdbc.queryForMap("SELECT * FROM import_batch WHERE id = ?", batch);
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
