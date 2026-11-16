package com.reconflow.paymentreconflow.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a ProblemDetail straight to the servlet response, for errors raised in filters before
 * any controller (and so before GlobalExceptionHandler) runs: Spring Security's 401 and 403.
 * Uses Boot's JsonMapper, so the output is identical to the controller-advice path (extension
 * members such as {@code code} and {@code requestId} flattened into the top level).
 */
@Component
public class ProblemDetailWriter {

    private final JsonMapper jsonMapper;

    ProblemDetailWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        if (problem.getInstance() == null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
