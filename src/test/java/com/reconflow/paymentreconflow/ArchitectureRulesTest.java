package com.reconflow.paymentreconflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules Spring Modulith cannot express. ArchUnit comes in with spring-modulith-core.
 *
 * <p>{@code allowEmptyShould(true)}: the packages are still empty in M2, and ArchUnit otherwise
 * fails a rule that matched no classes. Remove it once the modules have code.
 */
class ArchitectureRulesTest {

    static final String ROOT = "com.reconflow.paymentreconflow";

    static final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void sharedDependsOnNoOtherModule() {
        // Modulith treats an empty allowedDependencies as "unrestricted", so "shared depends on
        // nothing" (architecture.md §4) is stated here instead.
        noClasses()
                .that()
                .resideInAPackage(ROOT + ".shared..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        ROOT + ".organization..",
                        ROOT + ".security..",
                        ROOT + ".ingestion..",
                        ROOT + ".ledger..",
                        ROOT + ".reconciliation..",
                        ROOT + ".exceptions..",
                        ROOT + ".reporting..",
                        ROOT + ".audit..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void loginAndTokenInternalsStayInsideSecurity() {
        // authentication.plan §6. Modulith already hides security's sub-packages; this rule names
        // the two that matter most, so the failure message says exactly what went wrong.
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".security..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".security.auth..", ROOT + ".security.token..")
                .allowEmptyShould(true)
                .check(classes);
    }
}
