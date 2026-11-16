package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.TestcontainersConfiguration;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.StandInIdentityConfig;
import com.reconflow.paymentreconflow.support.TestData;
import com.reconflow.paymentreconflow.support.TestDatabase;
import com.reconflow.paymentreconflow.support.TestIssuer;
import com.reconflow.paymentreconflow.support.TestTokens;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * M5.26 and M10.3, over a real socket: MockMvc never parses multipart, so only a running Tomcat shows what a
 * client sees for an oversized upload. Without {@code server.tomcat.max-swallow-size} above the
 * request size, Tomcat stops reading the unread body and resets the connection, and the client
 * gets a connection error instead of the 413 ProblemDetail.
 *
 * <p>A separate Spring context (a real port), so it starts its own containers: the one IT that
 * pays that cost, deliberately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, StandInIdentityConfig.class})
class UploadSizeLimitIT {

    @LocalServerPort
    int port;

    JdbcTemplate jdbc;

    @Autowired
    void superuser(TestDatabase database) {
        this.jdbc = database.superuser(); // fixtures and assertions see every tenant (M10.7)
    }

    @Value("${spring.security.oauth2.resourceserver.jwt.audiences}")
    String audience;

    @Autowired
    S3Client s3;

    /** The CSV contract's limit, which the container and the code both enforce (M10.3). */
    static final int LIMIT = 50 * 1024 * 1024;

    @Test
    void aSixtyMegabyteUploadGetsA413ProblemNotAConnectionReset() throws Exception {
        Uploader uploader = newUploader();

        HttpResponse<String> res = upload(uploader, filler(60 * 1024 * 1024));

        assertRejectedAndNothingStored(uploader, res);
    }

    @Test
    void oneByteOverTheLimitIsRejectedByTheContainerAndNothingIsStored() throws Exception {
        Uploader uploader = newUploader();

        HttpResponse<String> res = upload(uploader, csvOfExactly(LIMIT + 1));

        assertRejectedAndNothingStored(uploader, res);
    }

    @Test
    void aFileOfExactlyTheLimitIsAccepted() throws Exception {
        Uploader uploader = newUploader();

        HttpResponse<String> res = upload(uploader, csvOfExactly(LIMIT));

        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        assertThat(jdbc.queryForObject(
                        "SELECT file_size_bytes FROM import_batch WHERE organization_id = ?", Long.class, uploader.org))
                .isEqualTo(LIMIT);
        assertThat(storedObjects(uploader.org)).hasSize(1);
    }

    record Uploader(UUID org, String token) {}

    private Uploader newUploader() {
        TestData data = new TestData(jdbc);
        UUID org = data.newOrganization();
        UUID user = data.newMember(org, Role.UPLOADER).id();
        return new Uploader(org, new TestTokens(TestIssuer.instance(), audience).forUser(user));
    }

    private void assertRejectedAndNothingStored(Uploader uploader, HttpResponse<String> res) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(413);
        assertThat(res.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(res.body()).contains("\"code\":\"file-too-large\"");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM import_batch WHERE organization_id = ?", Long.class, uploader.org))
                .isZero();
        assertThat(storedObjects(uploader.org)).isEmpty();
    }

    /** Objects under the organization's prefix in the bucket (StorageKeys starts every key with it). */
    private List<String> storedObjects(UUID org) {
        return s3
                .listObjectsV2(r -> r.bucket(TestcontainersConfiguration.BUCKET).prefix(org + "/"))
                .contents()
                .stream()
                .map(S3Object::key)
                .toList();
    }

    /** A gateway CSV of exactly {@code size} bytes: a valid header and row, padded with blank lines. */
    private static byte[] csvOfExactly(int size) {
        byte[] head = ("merchant_reference,amount,currency,status,transaction_at,gateway_transaction_id\n"
                        + "PAY-101,1000.00,AED,captured,2026-10-01T05:15:00Z,gw_0001\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[size];
        System.arraycopy(head, 0, content, 0, head.length);
        Arrays.fill(content, head.length, size, (byte) '\n');
        return content;
    }

    private static byte[] filler(int size) {
        byte[] content = new byte[size];
        Arrays.fill(content, (byte) 'a');
        return content;
    }

    private HttpResponse<String> upload(Uploader uploader, byte[] content) throws Exception {
        String boundary = "----reconflow" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"sourceType\"\r\n\r\nGATEWAY\r\n"
                        + "--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.csv\"\r\n"
                        + "Content-Type: text/csv\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/imports"))
                            .header("Authorization", TestTokens.bearer(uploader.token()))
                            .header("X-Organization-Id", uploader.org().toString())
                            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
