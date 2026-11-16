package com.reconflow.paymentreconflow.shared.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * The {@code migrate} role (M11.2, M11.5): by the time runners run, Flyway has applied every pending
 * migration and Hibernate has validated the entities against the result, so the job is done. Exit
 * explicitly with 0 instead of relying on the JVM finding no non-daemon threads left (an HTTP client
 * pool or a future library's thread would otherwise keep a "finished" deploy step running forever).
 *
 * <p>A failed migration never gets here: it fails the context's startup and the JVM exits non-zero,
 * which stops the deploy while the previous version keeps serving.
 */
@Component
@ConditionalOnBooleanProperty("reconflow.migrate-and-exit")
class MigrateAndExit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrateAndExit.class);

    private final ConfigurableApplicationContext context;

    MigrateAndExit(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Migrations applied and the schema validated; the migrate step is done");
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
