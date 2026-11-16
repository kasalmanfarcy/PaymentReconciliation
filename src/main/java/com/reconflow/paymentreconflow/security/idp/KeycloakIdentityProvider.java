package com.reconflow.paymentreconflow.security.idp;

import com.reconflow.paymentreconflow.security.config.AuthProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Keycloak's Admin REST API through Spring's {@link RestClient}: five calls, so a plain HTTP client
 * rather than {@code keycloak-admin-client}, which would bring RESTEasy and a second Jackson onto
 * the Boot 4 classpath for them (a deviation from the plan, recorded in ADR 0015).
 *
 * <p>Authenticates as the confidential {@code reconflow-admin} client (client-credentials grant),
 * whose service account has only {@code manage-users}: it can't change the realm, its clients or
 * anyone's roles. The token is cached until shortly before it expires.
 *
 * <p>No crypto and no token parsing here: Keycloak issues and checks the admin token, and the
 * resource server validates user tokens.
 */
@Component
class KeycloakIdentityProvider implements IdentityProvider {

    private static final String REQUIRED_ACTION_UPDATE_PASSWORD = "UPDATE_PASSWORD";

    private final AuthProperties.Keycloak props;
    private final RestClient http;
    private final Clock clock;
    private @Nullable String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    KeycloakIdentityProvider(AuthProperties properties, Clock clock) {
        this.props = properties.keycloak();
        this.clock = clock;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(props.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
        requests.setReadTimeout(props.timeout());
        this.http = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(requests)
                .build();
    }

    @Override
    public Account createUser(String email, String displayName, String temporaryPassword) {
        String[] names = names(displayName);
        Map<String, Object> user = Map.of(
                "username",
                email,
                "email",
                email,
                // Verified: an org admin of ReconFlow vouches for this address by creating the account.
                "emailVerified",
                true,
                "enabled",
                true,
                // Keycloak's user profile requires both; without them the first login stops at
                // "Account is not fully set up".
                "firstName",
                names[0],
                "lastName",
                names[1],
                "requiredActions",
                List.of(REQUIRED_ACTION_UPDATE_PASSWORD),
                "credentials",
                List.of(credential(temporaryPassword)));
        try {
            URI location = http.post()
                    .uri("/admin/realms/{realm}/users", props.realm())
                    .headers(h -> h.setBearerAuth(token()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
            if (location == null) {
                throw new IllegalStateException("Keycloak created a user but returned no Location");
            }
            String path = location.getPath();
            return new Account(path.substring(path.lastIndexOf('/') + 1), true);
        } catch (HttpClientErrorException.Conflict e) {
            // An account with this email already exists in Keycloak (created by hand, or an earlier
            // attempt whose database half failed and whose compensation failed too): link to it and
            // leave its password alone.
            return new Account(findByEmail(email), false);
        } catch (RestClientException e) {
            throw new IdentityProviderException("create user", e);
        }
    }

    @Override
    public void resetPassword(String subject, String temporaryPassword) {
        call(
                "reset password",
                () -> http.put()
                        .uri("/admin/realms/{realm}/users/{id}/reset-password", props.realm(), subject)
                        .headers(h -> h.setBearerAuth(token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(credential(temporaryPassword))
                        .retrieve()
                        .toBodilessEntity());
        logout(subject);
    }

    @Override
    public void disable(String subject) {
        call(
                "disable user",
                () -> http.put()
                        .uri("/admin/realms/{realm}/users/{id}", props.realm(), subject)
                        .headers(h -> h.setBearerAuth(token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("enabled", false))
                        .retrieve()
                        .toBodilessEntity());
        logout(subject);
    }

    @Override
    public void delete(String subject) {
        call(
                "delete user",
                () -> http.delete()
                        .uri("/admin/realms/{realm}/users/{id}", props.realm(), subject)
                        .headers(h -> h.setBearerAuth(token()))
                        .retrieve()
                        .toBodilessEntity());
    }

    /** Ends every session, which revokes the account's refresh tokens. */
    private void logout(String subject) {
        call(
                "log out user",
                () -> http.post()
                        .uri("/admin/realms/{realm}/users/{id}/logout", props.realm(), subject)
                        .headers(h -> h.setBearerAuth(token()))
                        .retrieve()
                        .toBodilessEntity());
    }

    private String findByEmail(String email) {
        try {
            List<?> found = http.get()
                    .uri("/admin/realms/{realm}/users?email={email}&exact=true", props.realm(), email)
                    .headers(h -> h.setBearerAuth(token()))
                    .retrieve()
                    .body(List.class);
            if (found == null || found.size() != 1 || !(found.getFirst() instanceof Map<?, ?> user)) {
                throw new IllegalStateException("Keycloak reported a conflict but no single user has that email");
            }
            return String.valueOf(user.get("id"));
        } catch (RestClientException e) {
            throw new IdentityProviderException("find user", e);
        }
    }

    /** The service account's access token, refreshed 30 s before it expires. */
    private synchronized String token() {
        if (token != null && clock.instant().isBefore(tokenExpiresAt)) {
            return token;
        }
        if (!StringUtils.hasText(props.adminClientSecret())) {
            throw new IdentityProviderException(
                    "not configured",
                    new IllegalStateException("reconflow.auth.keycloak.admin-client-secret is not set"));
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.adminClientId());
        form.add("client_secret", props.adminClientSecret());
        try {
            Map<?, ?> response = http.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", props.realm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("access_token") instanceof String accessToken)) {
                throw new IllegalStateException("Keycloak returned no access token");
            }
            long expiresIn = response.get("expires_in") instanceof Number n ? n.longValue() : 60;
            token = accessToken;
            tokenExpiresAt = clock.instant().plusSeconds(Math.max(0, expiresIn - 30));
            return accessToken;
        } catch (RestClientException e) {
            throw new IdentityProviderException("authenticate", e);
        }
    }

    private void call(String operation, Runnable request) {
        try {
            request.run();
        } catch (RestClientException e) {
            throw new IdentityProviderException(operation, e);
        }
    }

    private static Map<String, Object> credential(String temporaryPassword) {
        return Map.of("type", "password", "value", temporaryPassword, "temporary", true);
    }

    /** "Jane Q. Doe" → ("Jane Q.", "Doe"); a single word is used for both. */
    static String[] names(String displayName) {
        String trimmed = displayName.trim();
        int space = trimmed.lastIndexOf(' ');
        return space < 0
                ? new String[] {trimmed, trimmed}
                : new String[] {trimmed.substring(0, space).trim(), trimmed.substring(space + 1)};
    }
}
