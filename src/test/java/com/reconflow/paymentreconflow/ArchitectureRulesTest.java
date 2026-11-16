package com.reconflow.paymentreconflow;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.reconflow.paymentreconflow.reconciliation.domain.ReconciliationRun;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @Test
    void everyEndpointDeclaresItsPermission() {
        // M3.7: a forgotten annotation must be a red build, not an open endpoint. Only the auth
        // endpoints (login, refresh, logout: public; change-password: any authenticated user) are
        // exempt.
        methods()
                .that()
                .areDeclaredInClassesThat()
                .areAnnotatedWith(RestController.class)
                .and()
                .areDeclaredInClassesThat()
                .resideOutsideOfPackage(ROOT + ".security.auth..")
                .and()
                .areMetaAnnotatedWith(RequestMapping.class)
                .should()
                .beAnnotatedWith(RequiresPermission.class)
                .orShould()
                .beAnnotatedWith(PreAuthorize.class)
                .check(classes);
    }

    @Test
    void onlyRunLifecycleChangesARunsStatus() {
        // M6.10: one class, one table of allowed transitions, one place that audits. Scattered
        // status changes are how a run ends up COMPLETED with a failure reason.
        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(ROOT + ".reconciliation.application.RunLifecycle")
                .and()
                .doNotHaveFullyQualifiedName(ROOT + ".reconciliation.domain.ReconciliationRun")
                .should()
                .callMethodWhere(target(name("transitionTo"))
                        .or(target(name("complete")))
                        .or(target(name("fail")))
                        .and(target(owner(type(ReconciliationRun.class)))))
                .check(classes);
    }

    /**
     * M6.22 / rule MN2: money is compared by value. {@code new BigDecimal("1000.0").equals(new
     * BigDecimal("1000.00"))} is false (the scale differs), so a single {@code equals} on an amount
     * turns a match into a mismatch. {@code Objects.equals} and BigDecimal hash keys hide the same
     * trap and can't be caught statically; reviews and the engine tests cover those.
     */
    static final ArchRule NO_BIG_DECIMAL_EQUALS = noClasses()
            .that()
            .resideInAPackage(ROOT + "..")
            .should()
            .callMethod(BigDecimal.class, "equals", Object.class)
            .because("money is compared by value: use compareTo (reconciliation-rules.md MN2, TASKS.md M6.22)");

    @Test
    void moneyIsNeverComparedWithEquals() {
        NO_BIG_DECIMAL_EQUALS.check(classes);
    }
}
