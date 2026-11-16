package com.reconflow.paymentreconflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.reconflow.paymentreconflow.shared.context.RequiresBothPermissions;
import com.reconflow.paymentreconflow.shared.context.RequiresPermission;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M3.7: the endpoint index in docs/api-design.md §8.1 can't drift from the code. Every endpoint the
 * application maps must have a row, and the permission in that row must be the one its
 * {@code @RequiresPermission} names. (The index also lists endpoints of later milestones; those
 * are checked as they land.)
 */
class PermissionDocsTest {

    private static final String API = "/api/v1";
    private static final Pattern ROW =
            Pattern.compile("^\\| `(GET|POST|PUT|PATCH|DELETE) ([^`?]+)[^`]*` \\| ([^|]+) \\|");

    @Test
    void theEndpointIndexMatchesTheCode() throws Exception {
        Map<String, String> documented = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("docs/api-design.md"))) {
            Matcher m = ROW.matcher(line);
            if (m.find()) {
                documented.put(m.group(1) + " " + m.group(2).trim(), m.group(3).trim());
            }
        }

        List<String> problems = new ArrayList<>();
        int endpoints = 0;
        for (JavaClass type : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.reconflow.paymentreconflow")) {
            if (!type.isAnnotatedWith(RestController.class)) {
                continue;
            }
            Class<?> controller = Class.forName(type.getName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                endpoints++;
                String path =
                        (prefix + (mapping.path().length == 0 ? "" : mapping.path()[0])).replaceFirst("^" + API, "");
                String key = mapping.method()[0].name() + " " + path;
                String row = documented.get(key);
                String required = required(method);
                if (row == null) {
                    problems.add(key + " is not in the api-design.md §8.1 index");
                } else if (required != null && !row.equals(required)) {
                    problems.add(key + " needs " + required + " but the docs say " + row);
                }
            }
        }

        assertThat(endpoints).isPositive();
        assertThat(problems).isEmpty();
    }

    /** The permission cell the docs must show: {@code `X`}, or {@code `X` + `Y`} when both are needed. */
    private static @Nullable String required(Method method) {
        RequiresPermission one = method.getAnnotation(RequiresPermission.class);
        if (one != null) {
            return "`" + one.value() + "`";
        }
        RequiresBothPermissions both = method.getAnnotation(RequiresBothPermissions.class);
        if (both != null) {
            return "`" + both.value() + "` + `" + both.and() + "`";
        }
        return null;
    }
}
