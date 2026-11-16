package com.reconflow.paymentreconflow.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID: accepts the caller's {@code X-Request-Id} if it is safe, otherwise
 * generates one. The ID goes into the logging MDC (so every log line of this request carries
 * it), into the response header, and into every ProblemDetail as {@code requestId}.
 *
 * <p>Runs first ({@link Ordered#HIGHEST_PRECEDENCE}), before Spring Security's filter chain,
 * so even a 401 carries the ID.
 *
 * <p>MDC is thread-local: work handed to another thread (executors, the job worker) does not
 * inherit it. Jobs store the originating request ID in their payload and restore it (M5).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // api-design.md §1: letters, digits, '-' and '_', up to 64 characters. The value ends up
    // in logs, so anything else (e.g. a newline that could forge a log line) is replaced.
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && SAFE.matcher(incoming).matches()) ? incoming : newRequestId();
        MDC.put(MDC_KEY, requestId);
        // Set before the chain runs, so the header is present even if the response is
        // committed early (errors, streaming).
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled: a leftover ID would label the next request's logs.
            MDC.remove(MDC_KEY);
        }
    }

    /** 16 hex characters (64 random bits): short enough to read out, unique enough for logs. */
    static String newRequestId() {
        return HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }
}
