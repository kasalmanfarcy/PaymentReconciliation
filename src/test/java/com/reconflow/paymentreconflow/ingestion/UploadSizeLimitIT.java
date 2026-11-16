package com.reconflow.paymentreconflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.TestcontainersConfiguration;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.TestData;
import com.reconflow.paymentreconflow.support.TestTokens;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * M5.26, over a real socket: MockMvc never parses multipart, so only a running Tomcat shows what a
 * client sees for an oversized upload. Without {@code server.tomcat.max-swallow-size} above the
 * request size, Tomcat stops reading the unread body and resets the connection, and the client
 * gets a connection error instead of the 413 ProblemDetail.
 *
 * <p>A separate Spring context (a real port), so it starts its own containers: the one IT that
 * pays that cost, deliberately.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UploadSizeLimitIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Value("${reconflow.auth.jwt.issuer}")
    String issuer;

    @Value("${reconflow.auth.jwt.audience}")
    String audience;

    @Test
    void aSixtyMegabyteUploadGetsA413ProblemNotAConnectionReset() throws Exception {
        TestData data = new TestData(jdbc);
        UUID org = data.newOrganization();
        UUID user = data.newMember(org, Role.UPLOADER).id();
        String token = new TestTokens(jwtEncoder, issuer, audience).forUser(user);

        String boundary = "----reconflow" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"sourceType\"\r\n\r\nGATEWAY\r\n"
                        + "--" + boundary
                        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.csv\"\r\n"
                        + "Content-Type: text/csv\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        byte[] content = new byte[60 * 1024 * 1024];
        Arrays.fill(content, (byte) 'a');
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> res;
        try (HttpClient client = HttpClient.newHttpClient()) {
            res = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/imports"))
                            .header("Authorization", TestTokens.bearer(token))
                            .header("X-Organization-Id", org.toString())
                            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        assertThat(res.statusCode()).as(res.body()).isEqualTo(413);
        assertThat(res.body()).contains("\"code\":\"file-too-large\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_batch WHERE organization_id = ?", Long.class, org))
                .isZero();
    }
}
