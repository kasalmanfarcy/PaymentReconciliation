package com.reconflow.paymentreconflow.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.reconflow.paymentreconflow.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * M11.7: the OpenAPI document is generated from the controllers, so it can't invent an endpoint; these
 * checks make sure it is complete, says what the code and docs say, and is committed.
 *
 * <ul>
 *   <li><b>Drift:</b> the generated document must equal {@code docs/openapi.json}. After an intended
 *       API change, regenerate it and commit the diff:
 *       {@code ./mvnw verify -Dit.test=OpenApiSpecIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.write=true}
 *   <li><b>Complete:</b> every {@code /api/v1} handler method is in it.
 *   <li><b>Agrees with api-design.md §8.1:</b> each row's endpoint exists with exactly its success
 *       codes (a 202 documented as 200 is the classic spec lie) and at least its main error codes.
 * </ul>
 */
class OpenApiSpecIT extends AbstractIntegrationTest {

    static final Path COMMITTED = Path.of("docs/openapi.json");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    JsonNode spec;
    String generated;

    @BeforeEach
    void generate() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        ObjectNode tree = (ObjectNode) jsonMapper.readTree(body);
        tree.remove("servers"); // the request's host: not part of the contract
        spec = tree;
        generated = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n";
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/openapi.json"), generated);
    }

    @Test
    void theCommittedSpecIsWhatTheCodeGenerates() throws Exception {
        if (Boolean.getBoolean("openapi.write")) {
            Files.writeString(COMMITTED, generated);
        }
        assertThat(Files.exists(COMMITTED)).as("docs/openapi.json is missing").isTrue();
        assertThat(generated)
                .as("docs/openapi.json is out of date: diff it with target/openapi.json, then regenerate (see the"
                        + " class comment) and commit")
                .isEqualTo(Files.readString(COMMITTED));
    }

    @Test
    void everyReferenceResolves() {
        Matcher refs = Pattern.compile("\"#/components/schemas/([^\"]+)\"").matcher(generated);
        while (refs.find()) {
            assertThat(spec.path("components").path("schemas").has(refs.group(1)))
                    .as(refs.group(1))
                    .isTrue();
        }
    }

    @Test
    void everyApiEndpointIsDocumented() {
        Set<String> inCode = new TreeSet<>();
        mappings.getHandlerMethods()
                .keySet()
                .forEach(info -> info.getPatternValues().stream()
                        .filter(path -> path.startsWith("/api/v1/"))
                        .forEach(path -> info.getMethodsCondition()
                                .getMethods()
                                .forEach(method -> inCode.add(method.name() + " " + path))));
        Set<String> inSpec = new TreeSet<>();
        spec.get("paths")
                .properties()
                .forEach(path -> path.getValue()
                        .properties()
                        .forEach(op -> inSpec.add(op.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey())));
        assertThat(inCode).isNotEmpty();
        assertThat(inSpec).isEqualTo(inCode);
    }

    @Test
    void statusCodesAgreeWithTheApiDesignIndex() throws Exception {
        List<Row> rows = indexRows();
        assertThat(rows).hasSizeGreaterThan(25);
        for (Row row : rows) {
            JsonNode operation =
                    spec.path("paths").path(row.path()).path(row.method().toLowerCase(Locale.ROOT));
            assertThat(operation.isMissingNode())
                    .as(row.method() + " " + row.path())
                    .isFalse();
            Set<String> codes = new TreeSet<>();
            operation.get("responses").properties().forEach(r -> codes.add(r.getKey()));
            Set<String> success =
                    codes.stream().filter(c -> c.startsWith("2")).collect(Collectors.toCollection(TreeSet::new));
            assertThat(success)
                    .as("success codes of " + row.method() + " " + row.path())
                    .isEqualTo(row.success());
            assertThat(codes)
                    .as("error codes of " + row.method() + " " + row.path())
                    .containsAll(row.errors());
        }
    }

    record Row(String method, String path, Set<String> success, Set<String> errors) {}

    private static final Pattern CODE = Pattern.compile("\\b([2-5]\\d\\d)\\b");

    /** The table under "### 8.1 Index" of docs/api-design.md: method and path, permission, success, errors. */
    private static List<Row> indexRows() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("docs/api-design.md"));
        int start = lines.indexOf("### 8.1 Index");
        List<Row> rows = new ArrayList<>();
        for (String line : lines.subList(start + 1, lines.size())) {
            if (line.startsWith("### ")) {
                break;
            }
            if (!line.startsWith("| `")) {
                continue;
            }
            String[] cells = line.split("\\|");
            String endpoint = cells[1].trim().replace("`", "");
            String method = endpoint.substring(0, endpoint.indexOf(' '));
            String path =
                    "/api/v1" + endpoint.substring(endpoint.indexOf(' ') + 1).replaceAll("[ ?].*", "");
            rows.add(new Row(method, path, codes(cells[3]), codes(cells[4])));
        }
        return rows;
    }

    private static Set<String> codes(String cell) {
        Set<String> codes = new TreeSet<>();
        Matcher m = CODE.matcher(cell);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }
}
