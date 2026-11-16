package com.reconflow.paymentreconflow.shared.config;

import java.util.List;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fails startup before any bean is created when a property listed in
 * {@code reconflow.required-properties} is missing or blank.
 *
 * <p>Why: Spring leaves an unresolvable {@code ${DB_URL}} as the literal text, so a production
 * deploy that forgot a variable fails later with a misleading error ("'url' must start with
 * jdbc"), or worse, starts with a half-configured bean. application-prod.yml lists what it
 * needs, and this turns a missing value into one clear message naming the variable.
 *
 * <p>Registered in META-INF/spring.factories; runs after config files are loaded, so profile
 * files can declare the list.
 */
public class RequiredPropertiesCheck implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY = "reconflow.required-properties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] required = environment.getProperty(PROPERTY, String[].class, new String[0]);
        List<String> missing = java.util.Arrays.stream(required)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                // getProperty, not resolvePlaceholders: an unset ${X} must count as missing.
                .filter(name -> {
                    String value = environment.getProperty(name);
                    return value == null || value.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required configuration " + missing
                    + " (listed in " + PROPERTY + " for the active profiles "
                    + List.of(environment.getActiveProfiles()) + "). Set them as environment variables.");
        }
    }

    @Override
    public int getOrder() {
        // After ConfigDataEnvironmentPostProcessor, so application-<profile>.yml has been read.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
