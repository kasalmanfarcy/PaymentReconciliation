package com.reconflow.paymentreconflow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The deployed realm (M11.4, docs/security.md S-67): what a stranger can do with it is decided by this
 * file, so its properties are checked here rather than trusted. The local rehearsal of the deployment
 * shows the same live (password grant answered {@code invalid_client}, PKCE login works).
 */
class ProductionRealmTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void noClientOffersThePasswordGrant() throws Exception {
        for (JsonNode client : realm("realm-reconflow-prod.json").get("clients")) {
            assertThat(client.path("directAccessGrantsEnabled").asBoolean(false))
                    .as(client.get("clientId").asString())
                    .isFalse();
            assertThat(client.path("implicitFlowEnabled").asBoolean(false)).isFalse();
        }
    }

    @Test
    void scriptsLogInWithPkceToALoopbackRedirectOnly() throws Exception {
        JsonNode cli = client(realm("realm-reconflow-prod.json"), "reconflow-cli");
        assertThat(cli.get("publicClient").asBoolean()).isTrue();
        assertThat(cli.get("attributes").get("pkce.code.challenge.method").asString())
                .isEqualTo("S256");
        assertThat(cli.get("redirectUris")).hasSize(1);
        assertThat(cli.get("redirectUris").get(0).asString()).startsWith("http://127.0.0.1:");
    }

    @Test
    void noSecretIsCommitted() throws Exception {
        JsonNode realm = realm("realm-reconflow-prod.json");
        for (JsonNode user : realm.get("users")) {
            for (JsonNode credential : user.path("credentials")) {
                assertThat(credential.get("value").asString()).isEqualTo("${RECONFLOW_DEMO_PASSWORD}");
            }
        }
        assertThat(client(realm, "reconflow-admin").get("secret").asString())
                .isEqualTo("${RECONFLOW_KEYCLOAK_ADMIN_CLIENT_SECRET}");
    }

    /** The demo profile links the same fixed Keycloak ids in both realms (security.seed.LocalSeeder). */
    @Test
    void itHasTheLocalRealmsAccountsWithTheSameIds() throws Exception {
        assertThat(users(realm("realm-reconflow-prod.json"))).isEqualTo(users(realm("realm-reconflow.json")));
    }

    private JsonNode realm(String file) throws Exception {
        return json.readTree(Files.readString(Path.of("infra/keycloak", file)));
    }

    private static JsonNode client(JsonNode realm, String clientId) {
        for (JsonNode client : realm.get("clients")) {
            if (clientId.equals(client.get("clientId").asString())) {
                return client;
            }
        }
        throw new AssertionError("no client " + clientId);
    }

    private static Map<String, String> users(JsonNode realm) {
        Map<String, String> users = new HashMap<>();
        realm.get("users")
                .forEach(u ->
                        users.put(u.get("username").asString(), u.path("id").asString("")));
        return users;
    }
}
