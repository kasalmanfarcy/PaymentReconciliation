package com.reconflow.paymentreconflow.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * M9.10: metrics are scraped from the internal management port and never served on the public one,
 * as in the local and prod profiles. A real server on random ports (MockMvc has no ports), so this
 * class starts its own application context: the one deliberate exception to the shared one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ManagementPortIT {

    @Value("${local.server.port}")
    int serverPort;

    @Value("${local.management.port}")
    int managementPort;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void prometheusIsServedOnTheManagementPortWithoutAToken() throws Exception {
        HttpResponse<String> res = get(managementPort, "/actuator/prometheus");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .contains("reconflow_jobs_queued{")
                .contains("reconflow_exceptions_open{")
                .contains("http_server_requests_seconds_bucket")
                .contains("hikaricp_connections_active");
    }

    @Test
    void prometheusIsNotServedOnThePublicPort() throws Exception {
        assertThat(managementPort).isNotEqualTo(serverPort);
        assertThat(get(serverPort, "/actuator/prometheus").statusCode()).isIn(401, 403, 404);
    }

    @Test
    void theProbesAreOnTheManagementPort() throws Exception {
        assertThat(get(managementPort, "/actuator/health/liveness").statusCode())
                .isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode())
                .isEqualTo(200);
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
