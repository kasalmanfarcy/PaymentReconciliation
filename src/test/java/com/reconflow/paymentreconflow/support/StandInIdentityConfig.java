package com.reconflow.paymentreconflow.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Stand-ins for Keycloak in every integration test that isn't about Keycloak itself (M10.1): tokens
 * come from {@link TestIssuer}, validated by the application's real issuer-uri decoder, and the
 * Admin API is the in-memory {@link FakeIdentityProvider}. KeycloakIT uses the real thing instead.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StandInIdentityConfig {

    @Bean
    @Primary
    FakeIdentityProvider fakeIdentityProvider() {
        return new FakeIdentityProvider();
    }

    @Bean
    DynamicPropertyRegistrar standInIssuer() {
        return registry -> registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> TestIssuer.instance().issuer());
    }
}
