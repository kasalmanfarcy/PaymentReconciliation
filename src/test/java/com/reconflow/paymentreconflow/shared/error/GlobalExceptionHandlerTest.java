package com.reconflow.paymentreconflow.shared.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reconflow.paymentreconflow.shared.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every error path renders the api-design.md §3 shape. A web slice: MockMvc, the advice and the
 * RequestIdFilter, with a test-only controller that throws on demand. No database.
 *
 * <p>The slice doesn't load the app's SecurityConfig, so Boot's default security applies:
 * {@link WithMockUser} gets past authentication and {@code csrf()} past its CSRF check. The real
 * security chain is tested in SecurityConfigIT.
 */
@WebMvcTest(GlobalExceptionHandlerTest.ThrowingController.class)
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
@WithMockUser
class GlobalExceptionHandlerTest {

    static final UUID ID = UUID.fromString("01928c7e-5b1a-7c3e-9f00-2a4b6c8d0e1f");

    // A nested class of a test class is excluded from component scanning in every other test
    // context, so this controller only exists here.
    @RestController
    static class ThrowingController {

        record Body(@NotBlank String reference, @Min(1) int count, BigDecimal amount) {}

        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundException("Import batch", ID);
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException(
                    ProblemCodes.DUPLICATE_FILE, "This file was already imported.", Map.of("existingBatchId", ID));
        }

        @PostMapping("/test/body")
        void body(@Valid @RequestBody Body body) {}

        @GetMapping("/test/param")
        void param(@RequestParam int page) {}

        @GetMapping("/test/denied")
        void denied() {
            throw new AccessDeniedException("no permission");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("SELECT * FROM app_user -- secret detail");
        }
    }

    @Autowired
    MockMvc mockMvc;

    /** The members every error must have. */
    private static ResultActions assertProblem(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://reconflow.dev/problems/" + code))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("[0-9a-f]{16}")))
                .andExpect(header().exists(RequestIdFilter.HEADER));
    }

    @Test
    void apiExceptionKeepsItsStatusCodeAndDetail() throws Exception {
        assertProblem(mockMvc.perform(get("/test/not-found")), 404, "not-found")
                .andExpect(jsonPath("$.detail").value("Import batch " + ID + " was not found."))
                .andExpect(jsonPath("$.instance").value("/test/not-found"));
    }

    @Test
    void extensionMembersAreAdded() throws Exception {
        assertProblem(mockMvc.perform(get("/test/conflict")), 409, "duplicate-file")
                .andExpect(jsonPath("$.existingBatchId").value(ID.toString()));
    }

    @Test
    void requestIdInTheBodyMatchesTheHeader() throws Exception {
        mockMvc.perform(get("/test/not-found").header(RequestIdFilter.HEADER, "support-ticket-7"))
                .andExpect(header().string(RequestIdFilter.HEADER, "support-ticket-7"))
                .andExpect(jsonPath("$.requestId").value("support-ticket-7"));
    }

    @Test
    void beanValidationListsEachField() throws Exception {
        assertProblem(
                        mockMvc.perform(post("/test/body").with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reference\": \" \", \"count\": 0}")),
                        400,
                        "validation-failed")
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[?(@.field == 'reference')].code").value("not-blank"))
                .andExpect(jsonPath("$.errors[?(@.field == 'count')].code").value("min"));
    }

    @Test
    void malformedJsonIsAValidationFailure() throws Exception {
        assertProblem(
                        mockMvc.perform(post("/test/body").with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reference\": ")),
                        400,
                        "validation-failed")
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void wrongValueTypeNamesTheField() throws Exception {
        assertProblem(
                        mockMvc.perform(post("/test/body").with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reference\": \"x\", \"count\": \"many\"}")),
                        400,
                        "validation-failed")
                .andExpect(jsonPath("$.errors[0].field").value("count"))
                .andExpect(content().string(not(containsString("tools.jackson"))));
    }

    @Test
    void badQueryParameterIsAValidationFailure() throws Exception {
        assertProblem(mockMvc.perform(get("/test/param").param("page", "abc")), 400, "validation-failed")
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].code").value("type-mismatch"));
        assertProblem(mockMvc.perform(get("/test/param")), 400, "validation-failed")
                .andExpect(jsonPath("$.errors[0].code").value("required"));
    }

    @Test
    void unknownUrlIsNotFound() throws Exception {
        assertProblem(mockMvc.perform(get("/api/v1/nope")), 404, "not-found");
    }

    @Test
    void wrongMethodAndMediaTypeGetCatalogueCodes() throws Exception {
        assertProblem(mockMvc.perform(post("/test/not-found").with(csrf())), 405, "method-not-allowed");
        assertProblem(
                mockMvc.perform(post("/test/body").with(csrf()).contentType(MediaType.TEXT_PLAIN).content("x")),
                415,
                "unsupported-media-type");
    }

    @Test
    void securityExceptionsAreLeftToSpringSecurityNotTurnedInto500() throws Exception {
        mockMvc.perform(get("/test/denied")).andExpect(status().isForbidden());
    }

    @Test
    void unexpectedExceptionRevealsNothing() throws Exception {
        assertProblem(mockMvc.perform(get("/test/boom")), 500, "internal-error")
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(jsonPath("$.detail").value(containsString("Quote the requestId")));
    }
}
