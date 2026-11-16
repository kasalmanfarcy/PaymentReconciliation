package com.reconflow.paymentreconflow.reconciliation.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * Loads the engine's SQL from {@code src/main/resources/sql/reconciliation}, once, at startup. The
 * rules live partly in SQL (ADR 0012), so they live in files you can paste into psql for
 * {@code EXPLAIN (ANALYZE, BUFFERS)}, not in string literals scattered through Java.
 */
public final class SqlScripts {

    private SqlScripts() {}

    public static String load(String name) {
        try (InputStream in = new ClassPathResource("sql/reconciliation/" + name).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing engine SQL " + name, e);
        }
    }
}
