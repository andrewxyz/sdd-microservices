package com.example.learnhub.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.learnhub.common.correlation.CorrelationIdFilter;
import com.example.learnhub.common.correlation.CorrelationIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Constitution Principle VIII and FR-025: errors from a client-reachable service are RFC 7807 problem
 * details carrying the correlation ID. Bodies are validated against the published schema rather than a
 * hand-listed field set, so a contract change cannot drift away from the code.
 */
class ProblemDetailsAdviceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new ProblemDetailsAdvice())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private JsonNode errorBody(String path) throws Exception {
        MockHttpServletResponse response = mockMvc()
                .perform(get(path).header(CorrelationIds.HEADER, "test-corr-1234"))
                .andReturn().getResponse();
        return MAPPER.readTree(response.getContentAsString());
    }

    @Test
    @DisplayName("an unhandled exception becomes a 500 problem document")
    void unhandledExceptionBecomesProblem() throws Exception {
        MockHttpServletResponse response = mockMvc()
                .perform(get("/boom").header(CorrelationIds.HEADER, "test-corr-1234"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    @Test
    @DisplayName("an unhandled exception validates against the published schema")
    void unhandledExceptionMatchesSchema() throws Exception {
        JsonSchemaCheck.assertValid(ProblemDetailsSchemaTest.loadSchema(), errorBody("/boom"));
    }

    @Test
    @DisplayName("a ResponseStatusException keeps its status and validates against the schema")
    void responseStatusExceptionMatchesSchema() throws Exception {
        MockHttpServletResponse response = mockMvc()
                .perform(get("/missing").header(CorrelationIds.HEADER, "test-corr-1234"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        JsonSchemaCheck.assertValid(
                ProblemDetailsSchemaTest.loadSchema(), MAPPER.readTree(response.getContentAsString()));
    }

    @Test
    @DisplayName("correlationId in the body equals the response header")
    void correlationIdMatchesHeader() throws Exception {
        MockHttpServletResponse response = mockMvc()
                .perform(get("/boom").header(CorrelationIds.HEADER, "test-corr-1234"))
                .andReturn().getResponse();

        JsonNode body = MAPPER.readTree(response.getContentAsString());
        assertThat(body.get("correlationId").asText())
                .isEqualTo("test-corr-1234")
                .isEqualTo(response.getHeader(CorrelationIds.HEADER));
    }

    @Test
    @DisplayName("detail leaks neither the exception class, the message, nor a stack frame")
    void detailLeaksNoInternals() throws Exception {
        String detail = errorBody("/boom").path("detail").asText("");

        assertThat(detail)
                .doesNotContain("IllegalStateException")
                .doesNotContain("secret-connection-string")
                .doesNotContain("com.example.learnhub")
                .doesNotContain("at java.");
    }

    @Test
    @DisplayName("instance records the path that failed")
    void instanceRecordsPath() throws Exception {
        assertThat(errorBody("/boom").path("instance").asText()).isEqualTo("/boom");
    }

    @RestController
    static class BoomController {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret-connection-string=postgres://user:pw@host/db");
        }

        @GetMapping("/missing")
        String missing() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such thing");
        }
    }
}
