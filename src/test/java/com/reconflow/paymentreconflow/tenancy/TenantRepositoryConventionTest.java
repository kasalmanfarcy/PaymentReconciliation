package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.repository.Repository;

/**
 * M3.5 layer 1, as a build failure: every tenant-owned repository extends {@link Repository} (not
 * JpaRepository/CrudRepository, whose inherited {@code findById}, {@code findAll} and
 * {@code deleteById} take no organization: exactly the methods that leak), and every read or
 * delete method takes a parameter named {@code organizationId}.
 *
 * <p>Parameter names come from the {@code -parameters} compiler flag, which Boot's parent POM sets.
 */
class TenantRepositoryConventionTest {

    /** Not tenant-owned: the tenant itself, global users, cross-org membership, user-owned tokens, the queue. */
    static final Set<String> NOT_TENANT_OWNED = Set.of(
            "OrganizationRepository",
            "AppUserRepository",
            "MembershipRepository",
            "RefreshTokenRepository",
            "JobRepository");

    static final List<String> SCOPED_PREFIXES =
            List.of("find", "count", "exists", "delete", "stream", "get", "read", "query", "search");

    @Test
    void tenantRepositoriesExposeNoUnscopedMethods() throws Exception {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        for (JavaClass type : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.reconflow.paymentreconflow")) {
            if (!type.isInterface()
                    || !type.isAssignableTo(Repository.class)
                    || NOT_TENANT_OWNED.contains(type.getSimpleName())) {
                continue;
            }
            checked++;
            Class<?> repository = Class.forName(type.getName());
            for (Class<?> unsafe :
                    List.of(CrudRepository.class, ListCrudRepository.class, PagingAndSortingRepository.class)) {
                if (unsafe.isAssignableFrom(repository)) {
                    violations.add(repository.getSimpleName() + " extends " + unsafe.getSimpleName()
                            + ": extend Repository and declare organization-scoped methods");
                }
            }
            for (Method method : repository.getDeclaredMethods()) {
                boolean scoped = SCOPED_PREFIXES.stream().anyMatch(method.getName()::startsWith);
                if (!scoped) {
                    continue;
                }
                Parameter[] parameters = method.getParameters();
                assertThat(parameters)
                        .allSatisfy(p -> assertThat(p.isNamePresent())
                                .as("compile with -parameters")
                                .isTrue());
                if (Arrays.stream(parameters).noneMatch(p -> p.getName().equals("organizationId"))) {
                    violations.add(
                            repository.getSimpleName() + "." + method.getName() + " has no organizationId parameter");
                }
            }
        }

        assertThat(checked).as("tenant-owned repositories found").isPositive();
        assertThat(violations).isEmpty();
    }
}
