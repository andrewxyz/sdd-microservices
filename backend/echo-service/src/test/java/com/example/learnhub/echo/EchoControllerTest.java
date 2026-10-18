package com.example.learnhub.echo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.learnhub.common.correlation.CorrelationIdFilter;
import com.example.learnhub.common.correlation.CorrelationIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The echo service's two endpoints, matching contracts/echo-service.openapi.yaml.
 *
 * <p>Built with a standalone MockMvc rather than @SpringBootTest: the full context would need a
 * config server to bootstrap from, and what matters here is the response shape.
 */
class EchoControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc() {
        EchoProperties properties = new EchoProperties();
        properties.setGreeting("Hello from LearnHub");
        return MockMvcBuilders
                .standaloneSetup(new EchoController(properties, "echo-service:abc123", 9099))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private JsonNode body(String path, String correlationId) throws Exception {
        var request = get(path);
        if (correlationId != null) {
            request = request.header(CorrelationIds.HEADER, correlationId);
        }
        return MAPPER.readTree(mockMvc().perform(request).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("the public ping returns the four fields the contract promises")
    void publicPingShape() throws Exception {
        JsonNode body = body("/api/v1/echo/ping", "contract-test-1");

        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("instanceId", "port", "correlationId", "greeting");
        assertThat(body.get("instanceId").asText()).isEqualTo("echo-service:abc123");
        assertThat(body.get("port").asInt()).isEqualTo(9099);
        assertThat(body.get("greeting").asText()).isEqualTo("Hello from LearnHub");
    }

    @Test
    @DisplayName("the protected ping answers the same way when called directly, as the contract states")
    void securePingShape() throws Exception {
        JsonNode body = body("/api/v1/echo/secure/ping", "contract-test-1");

        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("instanceId", "port", "correlationId", "greeting");
    }

    @Test
    @DisplayName("a valid client correlation id is reflected in the body and the header")
    void reusesValidCorrelationId() throws Exception {
        MockHttpServletResponse response = mockMvc()
                .perform(get("/api/v1/echo/ping").header(CorrelationIds.HEADER, "client-id-987654"))
                .andReturn().getResponse();

        JsonNode body = MAPPER.readTree(response.getContentAsString());
        assertThat(body.get("correlationId").asText())
                .isEqualTo("client-id-987654")
                .isEqualTo(response.getHeader(CorrelationIds.HEADER));
    }

    @Test
    @DisplayName("a malformed correlation id is replaced by a generated one")
    void replacesMalformedCorrelationId() throws Exception {
        JsonNode body = body("/api/v1/echo/ping", "bad id!");

        assertThat(body.get("correlationId").asText()).isNotEqualTo("bad id!");
        assertThat(CorrelationIds.isValid(body.get("correlationId").asText())).isTrue();
    }

    @Test
    @DisplayName("the greeting follows the bound property, which is what makes live refresh visible")
    void greetingFollowsBoundProperty() throws Exception {
        EchoProperties properties = new EchoProperties();
        properties.setGreeting("changed-centrally");
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new EchoController(properties, "echo-service:abc123", 9099))
                .addFilters(new CorrelationIdFilter())
                .build();

        String json = mockMvc.perform(get("/api/v1/echo/ping")).andReturn().getResponse().getContentAsString();

        assertThat(MAPPER.readTree(json).get("greeting").asText()).isEqualTo("changed-centrally");
    }

    @Test
    @DisplayName("the service exposes no third endpoint")
    void onlyTwoEndpoints() throws Exception {
        int status = mockMvc().perform(get("/api/v1/echo/anything-else"))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(404);
    }
}
