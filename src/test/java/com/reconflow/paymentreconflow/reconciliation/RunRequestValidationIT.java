package com.reconflow.paymentreconflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M6.14: a bad run request is a {@code 400 validation-failed} ProblemDetail naming the field, and
 * creates nothing (api-design.md §8.8, §5 for the header).
 */
class RunRequestValidationIT extends AbstractRunApiIT {

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "reversed period      | {\"periodStart\":\"2026-10-31\",\"periodEnd\":\"2026-10-01\",\"currency\":\"AED\"} | periodEnd",
                "93 days              | {\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2027-01-01\",\"currency\":\"AED\"} | periodEnd",
                "unknown currency     | {\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2026-10-31\",\"currency\":\"XYZ\"} | currency",
                "not a currency       | {\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2026-10-31\",\"currency\":\"AEDX\"} | currency",
                "missing currency     | {\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2026-10-31\"}                      | currency",
                "missing period start | {\"periodEnd\":\"2026-10-31\",\"currency\":\"AED\"}                                  | periodStart",
                "same sources         | {\"periodStart\":\"2026-10-01\",\"periodEnd\":\"2026-10-31\",\"currency\":\"AED\",\"leftSource\":\"GATEWAY\",\"rightSource\":\"GATEWAY\"} | rightSource",
            })
    void anInvalidBodyIs400AndCreatesNothing(String name, String body, String field) throws Exception {
        var f = new ReconFixture(jdbc);

        assertRejected(f, requestRun(f, body, null), field);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {"too long | 129", "non-ASCII | 0"})
    void anInvalidIdempotencyKeyIs400(String name, int length) throws Exception {
        var f = new ReconFixture(jdbc);
        @Nullable String key = length > 0 ? "k".repeat(length) : "clé";

        assertRejected(f, requestRun(f, body("2026-10-01", "2026-10-31", "AED"), key), "Idempotency-Key");
    }

    @Test
    void ninetyTwoDaysAndA128CharacterKeyAreFine() throws Exception {
        var f = new ReconFixture(jdbc);

        MockHttpServletResponse res = requestRun(f, body("2026-10-01", "2026-12-31", "usd"), "k".repeat(128));

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(202);
        assertThat(json(res)).containsEntry("currency", "USD");
    }

    @Test
    void malformedJsonIs400() throws Exception {
        var f = new ReconFixture(jdbc);

        MockHttpServletResponse res = requestRun(f, "{\"periodStart\":\"yesterday\"}", null);

        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(json(res)).containsEntry("code", "validation-failed");
    }

    @SuppressWarnings("unchecked")
    private void assertRejected(ReconFixture f, MockHttpServletResponse res, String field) throws Exception {
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(400);
        assertThat(res.getContentType()).startsWith("application/problem+json");
        Map<String, Object> problem = json(res);
        assertThat(problem)
                .containsEntry("code", "validation-failed")
                .containsEntry("type", "https://reconflow.dev/problems/validation-failed")
                .containsKey("requestId");
        assertThat((List<Map<String, Object>>) problem.get("errors"))
                .extracting(e -> e.get("field"))
                .contains(field);
        assertThat(count("SELECT count(*) FROM reconciliation_run WHERE organization_id = ?", f.org))
                .isZero();
        assertThat(count("SELECT count(*) FROM job WHERE organization_id = ?", f.org))
                .isZero();
    }
}
