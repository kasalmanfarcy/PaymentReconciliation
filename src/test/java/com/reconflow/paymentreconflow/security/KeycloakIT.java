package com.reconflow.paymentreconflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.reconflow.paymentreconflow.TestcontainersConfiguration;
import com.reconflow.paymentreconflow.shared.context.Role;
import com.reconflow.paymentreconflow.support.TestData;
import com.reconflow.paymentreconflow.support.TestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M10.1 end to end, against a real Keycloak started from the committed realm file: real tokens,
 * real JWKS, the real Admin API client. A handful of tests only (the container takes ~10 s); every
 * other test uses the stand-in issuer.
 *
 * <p>Its own Spring context (the issuer and the Admin API point at this container), so it also gets
 * its own PostgreSQL and MinIO.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, KeycloakIT.KeycloakContainerConfig.class})
class KeycloakIT {

    static final String REALM = "reconflow";
    static final String DEMO_PASSWORD = "reconflow-local-demo";
    static final String ADMIN_PASSWORD = "kc-admin-" + UUID.randomUUID();
    static final String CLIENT_SECRET = "kc-client-" + UUID.randomUUID();

    @TestConfiguration(proxyBeanMethods = false)
    static class KeycloakContainerConfig {

        @Bean
        GenericContainer<?> keycloak() {
            GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.2")
                    .withCommand("start-dev", "--import-realm")
                    .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                    .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
                    .withEnv("RECONFLOW_KEYCLOAK_ADMIN_CLIENT_SECRET", CLIENT_SECRET)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("infra/keycloak/realm-reconflow.json")),
                            "/opt/keycloak/data/import/realm-reconflow.json")
                    .withExposedPorts(8080)
                    .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                            .forPort(8080)
                            .withStartupTimeout(Duration.ofMinutes(3)));
            keycloak.start();
            return keycloak;
        }

        @Bean
        DynamicPropertyRegistrar keycloakProperties(GenericContainer<?> keycloak) {
            return registry -> {
                registry.add(
                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        () -> base(keycloak) + "/realms/" + REALM);
                registry.add("reconflow.auth.keycloak.base-url", () -> base(keycloak));
                registry.add("reconflow.auth.keycloak.admin-client-secret", () -> CLIENT_SECRET);
            };
        }
    }

    static String base(GenericContainer<?> keycloak) {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    GenericContainer<?> keycloak;

    @Autowired
    JsonMapper json;

    JdbcTemplate jdbc;

    @Autowired
    void superuser(TestDatabase database) {
        this.jdbc = database.superuser();
    }

    /** The realm's fixed ids (infra/keycloak/realm-reconflow.json). */
    static final String ACME_ADMIN = "0193b9e2-7c41-7a10-8d2e-000000000a01";

    static final String ACME_VIEWER = "0193b9e2-7c41-7a10-8d2e-000000000a04";

    @Test
    void aRealTokenFromTheDemoClientReadsExceptions() throws Exception {
        UUID org = new TestData(jdbc).newOrganization();
        UUID viewer = linkedUser("viewer@acme.test", ACME_VIEWER);
        new TestData(jdbc).addMembership(viewer, org, Role.VIEWER);

        String token =
                password("viewer@acme.test", DEMO_PASSWORD).get("access_token").asString();

        assertThat(call("/api/v1/exceptions", token, org).getStatus()).isEqualTo(200);
    }

    @Test
    void aRealTokenForAnAccountReconFlowDoesNotKnowIs401() throws Exception {
        // In the realm, never provisioned in this database.
        String token = password("investigator@globex.test", DEMO_PASSWORD)
                .get("access_token")
                .asString();

        MockHttpServletResponse res = call("/api/v1/exceptions", token, null);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void aTokenIssuedForAnotherAudienceIs401() throws Exception {
        // The service-account token: same issuer, valid signature, but aud is realm-management.
        String token = clientCredentials().get("access_token").asString();

        assertThat(call("/api/v1/exceptions", token, null).getStatus()).isEqualTo(401);
    }

    @Test
    void aMemberCreatedThroughTheApiExistsInKeycloakAndMustChooseAPassword() throws Exception {
        UUID org = new TestData(jdbc).newOrganization();
        UUID admin = linkedUser("admin@acme.test", ACME_ADMIN);
        new TestData(jdbc).addMembership(admin, org, Role.ORG_ADMIN);
        String adminToken =
                password("admin@acme.test", DEMO_PASSWORD).get("access_token").asString();
        String email = "kc-new-" + UUID.randomUUID() + "@example.test";

        MockHttpServletResponse created = mockMvc.perform(post("/api/v1/members")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Organization-Id", org.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", email, "displayName", "New Member", "role", "VIEWER"))))
                .andReturn()
                .getResponse();
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        JsonNode body = json.readTree(created.getContentAsString());
        String temporaryPassword = body.get("temporaryPassword").asString();
        UUID userId = UUID.fromString(body.get("userId").asString());

        // The temporary password is right, but Keycloak issues no token until it's changed
        // (the "Update Password" required action): that replaced password-change-required.
        JsonNode attempt = password(email, temporaryPassword);
        assertThat(attempt.get("error").asString()).isEqualTo("invalid_grant");
        assertThat(attempt.get("error_description").asString()).isEqualTo("Account is not fully set up");
        String subject =
                jdbc.queryForObject("SELECT external_subject FROM app_user WHERE id = ?", String.class, userId);
        JsonNode account = adminGet("/admin/realms/" + REALM + "/users/" + subject);
        assertThat(account.get("email").asString()).isEqualTo(email);
        assertThat(account.get("emailVerified").asBoolean()).isTrue();

        // Disabling the member disables the Keycloak account too.
        MockHttpServletResponse disabled = mockMvc.perform(post("/api/v1/members/" + userId + "/disable")
                        .header("Authorization", "Bearer " + adminToken)
                        .header("X-Organization-Id", org.toString()))
                .andReturn()
                .getResponse();
        assertThat(disabled.getStatus()).as(disabled.getContentAsString()).isEqualTo(200);
        assertThat(adminGet("/admin/realms/" + REALM + "/users/" + subject)
                        .get("enabled")
                        .asBoolean())
                .isFalse();
    }

    @Test
    void fiveWrongPasswordsLockTheAccountTemporarily() throws Exception {
        // Replaces M3.3f's LoginAttemptService: the realm's brute-force detection (5 failures, 15 min).
        for (int i = 0; i < 5; i++) {
            assertThat(password("uploader@globex.test", "wrong-password-" + i)
                            .get("error")
                            .asString())
                    .isEqualTo("invalid_grant");
        }

        JsonNode withTheRightPassword = password("uploader@globex.test", DEMO_PASSWORD);
        assertThat(withTheRightPassword.has("access_token")).isFalse();
        assertThat(withTheRightPassword.get("error").asString()).isEqualTo("invalid_grant");
    }

    private UUID linkedUser(String email, String subject) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user (id, email, display_name, external_subject) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT DO NOTHING",
                id,
                email,
                email,
                subject);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE external_subject = ?", UUID.class, subject);
    }

    private MockHttpServletResponse call(String path, String token, UUID orgId) throws Exception {
        var request = get(path).header("Authorization", "Bearer " + token);
        if (orgId != null) {
            request.header("X-Organization-Id", orgId.toString());
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private JsonNode password(String username, String password) throws Exception {
        return token(Map.of(
                "grant_type", "password", "client_id", "reconflow-demo", "username", username, "password", password));
    }

    private JsonNode clientCredentials() throws Exception {
        return token(Map.of(
                "grant_type", "client_credentials", "client_id", "reconflow-admin", "client_secret", CLIENT_SECRET));
    }

    private JsonNode token(Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + java.net.URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(
                        URI.create(base(keycloak) + "/realms/" + REALM + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /** Admin API as the master realm's bootstrap admin: an outside view of what the app did. */
    private JsonNode adminGet(String path) throws Exception {
        String admin = send(HttpRequest.newBuilder(
                                URI.create(base(keycloak) + "/realms/master/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=password&client_id=admin-cli&username=admin&password=" + ADMIN_PASSWORD))
                        .build())
                .get("access_token")
                .asString();
        return send(HttpRequest.newBuilder(URI.create(base(keycloak) + path))
                .header("Authorization", "Bearer " + admin)
                .GET()
                .build());
    }

    private JsonNode send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return json.readTree(
                    client.send(request, HttpResponse.BodyHandlers.ofString()).body());
        }
    }
}
