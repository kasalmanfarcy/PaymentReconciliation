package com.reconflow.paymentreconflow.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * M7.15: the interesting cells of the transition table, through the API (ExceptionStateMachineTest
 * covers all of them without HTTP). Each rejected request leaves the case, its history and its
 * audit trail exactly as they were.
 */
class ExceptionInvalidTransitionIT extends AbstractExceptionApiIT {

    enum Start {
        OPEN,
        IN_REVIEW,
        RESOLVED
    }

    interface Request {
        MockHttpServletResponse send(AbstractExceptionApiIT test, ExceptionFixture f, UUID id, String etag)
                throws Exception;
    }

    record Case(String name, Start start, Request request, int status, String code, String detailHint) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                        new Case(
                                "resolve a RESOLVED case",
                                Start.RESOLVED,
                                (t, f, id, e) -> t.resolve(f, id, f.admin(), e, "TIMING_DIFFERENCE", null),
                                409,
                                "invalid-transition",
                                "\"currentStatus\":\"RESOLVED\""),
                        new Case(
                                "reopen an OPEN case",
                                Start.OPEN,
                                (t, f, id, e) -> t.reopen(f, id, f.admin(), e, "why"),
                                409,
                                "invalid-transition",
                                "\"currentStatus\":\"OPEN\""),
                        new Case(
                                "IN_REVIEW to OPEN without a reason",
                                Start.IN_REVIEW,
                                (t, f, id, e) -> t.patchCase(f, id, f.admin(), e, "{\"status\":\"OPEN\"}"),
                                400,
                                "validation-failed",
                                "\"field\":\"reason\""),
                        new Case(
                                "resolve with a code that needs a note, without one",
                                Start.OPEN,
                                (t, f, id, e) -> t.resolve(f, id, f.admin(), e, "OTHER", null),
                                400,
                                "validation-failed",
                                "required-for-code"),
                        new Case(
                                "resolve an OPEN case without a code",
                                Start.OPEN,
                                (t, f, id, e) -> t.resolve(f, id, f.admin(), e, null, "a reason but no code"),
                                400,
                                "validation-failed",
                                "\"field\":\"resolutionCode\""),
                        new Case(
                                "resolve with an unknown code",
                                Start.OPEN,
                                (t, f, id, e) -> t.resolve(f, id, f.admin(), e, "BANANA", null),
                                400,
                                "validation-failed",
                                "unknown-resolution-code"),
                        new Case(
                                "PATCH status RESOLVED",
                                Start.OPEN,
                                (t, f, id, e) -> t.patchCase(f, id, f.admin(), e, "{\"status\":\"RESOLVED\"}"),
                                400,
                                "validation-failed",
                                "/resolve"),
                        new Case(
                                "PATCH a RESOLVED case into review",
                                Start.RESOLVED,
                                (t, f, id, e) -> t.patchCase(f, id, f.admin(), e, "{\"status\":\"IN_REVIEW\"}"),
                                409,
                                "invalid-transition",
                                "\"currentStatus\":\"RESOLVED\""),
                        new Case(
                                "PATCH an OPEN case to OPEN",
                                Start.OPEN,
                                (t, f, id, e) ->
                                        t.patchCase(f, id, f.admin(), e, "{\"status\":\"OPEN\",\"reason\":\"x\"}"),
                                409,
                                "invalid-transition",
                                "\"allowedTransitions\":[\"START_REVIEW\",\"RESOLVE\"]"))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void isRejectedAndChangesNothing(Case c) throws Exception {
        var f = fixture();
        UUID id = f.amountMismatch("PAY-103");
        switch (c.start()) {
            case IN_REVIEW -> patchCase(f, id, f.admin(), "\"0\"", "{\"status\":\"IN_REVIEW\"}");
            case RESOLVED -> resolve(f, id, f.admin(), "\"0\"", "GATEWAY_FEE", null);
            case OPEN -> {}
        }
        Map<String, Object> before = f.caseRow(id);
        long history = f.historyRows(id);
        long audits = f.auditRows(id);

        MockHttpServletResponse res = c.request().send(this, f, id, etag(f, id));

        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(c.status());
        assertThat(code(json(res))).isEqualTo(c.code());
        assertThat(res.getContentAsString()).contains(c.detailHint());
        assertThat(f.caseRow(id)).isEqualTo(before);
        assertThat(f.historyRows(id)).isEqualTo(history);
        assertThat(f.auditRows(id)).isEqualTo(audits);
    }
}
