package com.reconflow.paymentreconflow.security.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing (ADR 0013). Spring Security does the cryptography; nothing here is hand-rolled.
 *
 * <p>{@link DelegatingPasswordEncoder} prefixes every hash with its algorithm ({@code {bcrypt}}),
 * and bcrypt hashes carry their cost ({@code $2a$12$}). Together they make the hash
 * self-describing, so the algorithm or cost can change later: a successful login checks
 * {@code upgradeEncoding} and rehashes, with no mass password reset.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    /** Measured at ~208 ms per hash on the development machine (ADR 0013); target ≈250 ms. */
    public static final int BCRYPT_COST = 12;

    @Bean
    PasswordEncoder passwordEncoder() {
        String id = "bcrypt";
        Map<String, PasswordEncoder> encoders = Map.of(id, new BCryptPasswordEncoder(BCRYPT_COST));
        return new DelegatingPasswordEncoder(id, encoders);
    }
}
