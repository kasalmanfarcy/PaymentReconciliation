package com.reconflow.paymentreconflow;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Turns the module rules in docs/architecture.md §3–§4 into a build failure. No Spring context
 * and no database: Modulith analyses the compiled classes with ArchUnit, so this is a unit test.
 */
class ModularityTest {

    static final ApplicationModules modules = ApplicationModules.of(PaymentreconflowApplication.class);

    @Test
    void modulesRespectTheirBoundaries() {
        // Fails on: a cycle between modules, use of another module's internal (sub-package)
        // type, or a dependency not listed in that module's allowedDependencies.
        modules.verify();
    }

    @Test
    void writeDocumentation() {
        // PlantUML/C4 diagrams of the actual module graph in target/spring-modulith-docs.
        // Compare them with docs/architecture.md §4 when reviewing.
        new Documenter(modules).writeDocumentation();
    }
}
