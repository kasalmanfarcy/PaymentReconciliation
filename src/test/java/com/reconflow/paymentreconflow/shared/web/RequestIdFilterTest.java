package com.reconflow.paymentreconflow.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    /** Runs the filter and returns the MDC value seen by the rest of the chain. */
    private String run(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });
        return seen.get();
    }

    @Test
    void generatesAnIdWhenNoneIsSent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String inChain = run(new MockHttpServletRequest(), response);

        assertThat(inChain).matches("[0-9a-f]{16}");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(inChain);
    }

    @Test
    void echoesASafeIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "client-ID_42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(request, response)).isEqualTo("client-ID_42");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("client-ID_42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad\nvalue", "", "has space", "dots.are.not.allowed", "<script>"})
    void replacesAnUnsafeIncomingId(String unsafe) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, unsafe);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(run(request, response)).matches("[0-9a-f]{16}");
    }

    @Test
    void rejectsAnIdLongerThan64Characters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "a".repeat(65));

        assertThat(run(request, new MockHttpServletResponse())).matches("[0-9a-f]{16}");
    }

    @Test
    void clearsTheMdcAfterTheRequest() throws Exception {
        run(new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
