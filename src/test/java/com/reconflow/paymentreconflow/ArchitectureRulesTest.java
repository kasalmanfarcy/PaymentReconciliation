package com.reconflow.paymentreconflow;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules Spring Modulith cannot express. ArchUnit comes in with spring-modulith-core.
 *
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
                .check(classes);
    }

    @Test
    void onlyTheAuditModuleTouchesAuditEvents() {
        // M4.2: the append-only log has exactly one write path, AuditService. Modulith already
        // hides audit.internal; this names the rule so the failure says what went wrong.
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".audit..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + ".audit.internal..")
                .check(classes);
    }

    @Test
    void identityIsReadOnlyThroughCurrentUserAndTenantContext() {
        // M3.3i: one seam. Business code asks shared.context.CurrentUser / TenantContext, never the
        // SecurityContext, an Authentication's JWT, or the resource server. M10 replaces what's
        // behind the seam (Keycloak) and nothing outside security notices; tests bind a tenant
        // without building a JWT; workers bind one from a job row.
        noClasses()
                .that()
                .resideOutsideOfPackage(ROOT + ".security..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.security.core.context..",
                        "org.springframework.security.oauth2.jwt..",
                        "org.springframework.security.oauth2.server.resource..")
                .check(classes);
    }
}
