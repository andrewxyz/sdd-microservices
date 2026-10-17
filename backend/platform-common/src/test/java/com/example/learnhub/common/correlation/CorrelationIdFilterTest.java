package com.example.learnhub.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-020 for servlet services: reuse, replacement, response header, and MDC lifecycle. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(filter)
                .build();
    }

    @Test
    @DisplayName("a valid client id is reused and echoed back")
    void reusesValidClientId() throws Exception {
        String body = mockMvc().perform(get("/probe").header(CorrelationIds.HEADER, "client-id-1234"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo("client-id-1234");
    }

    @Test
    @DisplayName("the resolved id is returned in the response header")
    void returnsIdInResponseHeader() throws Exception {
        MockHttpServletResponse response = mockMvc()
                .perform(get("/probe").header(CorrelationIds.HEADER, "client-id-1234"))
                .andReturn().getResponse();

        assertThat(response.getHeader(CorrelationIds.HEADER)).isEqualTo("client-id-1234");
    }

    @Test
    @DisplayName("a malformed client id is replaced by a generated one")
    void replacesMalformedClientId() throws Exception {
        MockHttpServletResponse response = mockMvc()
                .perform(get("/probe").header(CorrelationIds.HEADER, "bad id!"))
                .andReturn().getResponse();

        String resolved = response.getHeader(CorrelationIds.HEADER);
        assertThat(resolved).isNotEqualTo("bad id!");
        assertThat(CorrelationIds.isValid(resolved)).isTrue();
    }

    @Test
    @DisplayName("a request without the header gets a generated id")
    void generatesWhenHeaderAbsent() throws Exception {
        MockHttpServletResponse response = mockMvc().perform(get("/probe")).andReturn().getResponse();

        assertThat(CorrelationIds.isValid(response.getHeader(CorrelationIds.HEADER))).isTrue();
    }

    @Test
    @DisplayName("MDC carries the id during the request and is cleared afterwards")
    void mdcSetDuringRequestAndClearedAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        request.addHeader(CorrelationIds.HEADER, "during-request-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        StringBuilder seen = new StringBuilder();

        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.append(MDC.get(CorrelationIds.MDC_KEY));
            }
        };
        filter.doFilter(request, response, chain);

        assertThat(seen.toString()).isEqualTo("during-request-1");
        assertThat(MDC.get(CorrelationIds.MDC_KEY)).as("cleared after the request").isNull();
    }

    @Test
    @DisplayName("MDC is cleared even when the handler throws")
    void mdcClearedOnException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/probe");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("handler blew up");
            }
        };

        try {
            filter.doFilter(request, response, chain);
        } catch (Exception expected) {
            // the filter must not swallow it
        }

        assertThat(MDC.get(CorrelationIds.MDC_KEY)).isNull();
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        String probe(HttpServletRequest request, HttpServletResponse response) {
            return MDC.get(CorrelationIds.MDC_KEY);
        }
    }
}
