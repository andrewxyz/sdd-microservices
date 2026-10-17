package com.example.learnhub.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.common.correlation.CorrelationIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The gateway's client-visible contract: routing, correlation IDs, CORS, rate limiting and the one
 * error shape (FR-019 to FR-025, SC-004, SC-005).
 *
 * <p>Upstreams are WireMock rather than the real echo service, because several cases need behaviour
 * a healthy service cannot produce on demand: a refused connection, a response slower than the
 * timeout, and a route whose service has no instance at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("behaviourit")
class GatewayBehaviourIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONFIG = Path.of("target", "behaviour-it", "config.yml");

    private static WireMockServer upstream;
    private static int deadPort;
    private static JsonNode problemSchema;

    @BeforeAll
    static void startUpstreamAndWriteConfig() throws Exception {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        upstream.stubFor(get(urlPathEqualTo("/api/v1/echo/ping"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/echo/secure/ping"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/quoted/ping"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/slow/thing"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(8000).withBody("too slow")));

        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();   // closed again immediately: connections are refused
        }

        Files.createDirectories(CONFIG.getParent());
        Files.writeString(CONFIG, """
                spring:
                  cloud:
                    gateway:
                      server:
                        webflux:
                          globalcors:
                            cors-configurations:
                              '[/**]':
                                allowed-origins: ["http://allowed.example"]
                                allowed-methods: [GET, OPTIONS]
                                allowed-headers: "*"
                          httpclient:
                            connect-timeout: 2000
                            response-timeout: 5s
                          routes:
                            - id: echo-public
                              uri: http://localhost:%d
                              order: 0
                              predicates: ["Path=/api/v1/echo/ping"]
                              metadata:
                                public: true
                            - id: echo-public-quoted
                              uri: http://localhost:%d
                              order: 0
                              predicates: ["Path=/api/v1/quoted/ping"]
                              metadata:
                                public: "true"
                            - id: echo
                              uri: http://localhost:%d
                              order: 1
                              predicates: ["Path=/api/v1/echo/**"]
                            - id: slow
                              uri: http://localhost:%d
                              order: 1
                              predicates: ["Path=/api/v1/slow/**"]
                              metadata:
                                public: true
                            - id: dead
                              uri: http://localhost:%d
                              order: 1
                              predicates: ["Path=/api/v1/dead/**"]
                              metadata:
                                public: true
                            - id: ghost
                              uri: lb://no-such-service
                              order: 1
                              predicates: ["Path=/api/v1/ghost/**"]
                              metadata:
                                public: true
                platform:
                  gateway:
                    rate-limit:
                      capacity: 20
                      refill-per-second: 1
                      idle-eviction: PT10M
                """.formatted(upstream.port(), upstream.port(), upstream.port(),
                        upstream.port(), deadPort));

        try (InputStream in = GatewayBehaviourIT.class.getClassLoader()
                .getResourceAsStream("schemas/problem-details.schema.json")) {
            problemSchema = MAPPER.readTree(in);
        }
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @Autowired
    private WebTestClient injectedClient;

    private WebTestClient client;

    @org.junit.jupiter.api.BeforeEach
    void giveTheClientMoreTimeThanTheGateway() {
        // The gateway's own response timeout is 5s, so a client timeout of 5s would fire first and
        // the 504 case would surface as a client-side error instead of the gateway behaviour.
        client = injectedClient.mutate().responseTimeout(Duration.ofSeconds(20)).build();
    }

    private EntityExchangeResult<byte[]> call(String path, String correlationId) {
        var request = client.get().uri(path);
        if (correlationId != null) {
            request = request.header(CorrelationIds.HEADER, correlationId);
        }
        return request.exchange().expectBody().returnResult();
    }

    private JsonNode bodyOf(EntityExchangeResult<byte[]> result) throws IOException {
        return MAPPER.readTree(new String(result.getResponseBodyContent(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private void assertIsProblem(EntityExchangeResult<byte[]> result, int expectedStatus) throws IOException {
        assertThat(result.getStatus().value()).isEqualTo(expectedStatus);
        assertThat(result.getResponseHeaders().getContentType())
                .as("errors must be application/problem+json")
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        JsonNode body = bodyOf(result);
        JsonSchemaCheck.assertValid(problemSchema, body);
        assertThat(body.get("correlationId").asText())
                .as("the body must quote the same id as the header")
                .isEqualTo(result.getResponseHeaders().getFirst(CorrelationIds.HEADER));
    }

    // ------------------------------------------------------------------ routing

    @Test
    @DisplayName("a public route reaches the service")
    void publicRouteIsReached() {
        EntityExchangeResult<byte[]> result = call("/api/v1/echo/ping", null);

        assertThat(result.getStatus().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a protected route is refused with 401 until authentication exists")
    void protectedRouteIsRefused() throws IOException {
        assertIsProblem(call("/api/v1/echo/secure/ping", null), 401);
    }

    @Test
    @DisplayName("public: \"true\" as a quoted string marks a route public too")
    void quotedPublicMetadataWorks() {
        assertThat(call("/api/v1/quoted/ping", null).getStatus().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("an unknown path is 404 in the same error shape")
    void unknownPathIsNotFound() throws IOException {
        assertIsProblem(call("/nope", null), 404);
    }

    // ----------------------------------------------------------- correlation id

    @Test
    @DisplayName("a valid client correlation id is reused and returned")
    void reusesValidCorrelationId() {
        EntityExchangeResult<byte[]> result = call("/api/v1/echo/ping", "client-supplied-1");

        assertThat(result.getResponseHeaders().getFirst(CorrelationIds.HEADER))
                .isEqualTo("client-supplied-1");
    }

    @Test
    @DisplayName("the correlation id is forwarded to the service")
    void forwardsCorrelationIdUpstream() {
        call("/api/v1/echo/ping", "forwarded-id-123");

        upstream.verify(getRequestedFor(urlPathEqualTo("/api/v1/echo/ping"))
                .withHeader(CorrelationIds.HEADER,
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("forwarded-id-123")));
    }

    @Test
    @DisplayName("a malformed correlation id is replaced by a generated one")
    void replacesMalformedCorrelationId() {
        EntityExchangeResult<byte[]> result = call("/api/v1/echo/ping", "bad id!");

        String resolved = result.getResponseHeaders().getFirst(CorrelationIds.HEADER);
        assertThat(resolved).isNotEqualTo("bad id!");
        assertThat(CorrelationIds.isValid(resolved)).isTrue();
    }

    @Test
    @DisplayName("even an error response carries a correlation id")
    void errorsCarryCorrelationId() {
        EntityExchangeResult<byte[]> result = call("/api/v1/echo/secure/ping", "error-path-123");

        assertThat(result.getResponseHeaders().getFirst(CorrelationIds.HEADER)).isEqualTo("error-path-123");
    }

    // ------------------------------------------------------------------- errors

    @Test
    @DisplayName("a refused connection is 503, not the framework default of 500")
    void refusedConnectionIsServiceUnavailable() throws IOException {
        Instant started = Instant.now();

        EntityExchangeResult<byte[]> result = call("/api/v1/dead/thing", null);

        assertIsProblem(result, 503);
        assertThat(Duration.between(started, Instant.now()))
                .as("SC-005 allows five seconds").isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a service with no registered instance is 503 within five seconds")
    void noInstanceIsServiceUnavailable() throws IOException {
        Instant started = Instant.now();

        assertIsProblem(call("/api/v1/ghost/thing", null), 503);

        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("an upstream slower than the response timeout is 504")
    void slowUpstreamIsGatewayTimeout() throws IOException {
        assertIsProblem(call("/api/v1/slow/thing", null), 504);
    }

    // -------------------------------------------------------------------- CORS

    @Test
    @DisplayName("preflight from an allowed origin succeeds even on a protected route")
    void preflightFromAllowedOriginIsNeverRefused() {
        int status = client.method(HttpMethod.OPTIONS).uri("/api/v1/echo/secure/ping")
                .header(HttpHeaders.ORIGIN, "http://allowed.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange().returnResult(String.class).getStatus().value();

        assertThat(status).as("a browser preflight must never get 401").isEqualTo(200);
    }

    @Test
    @DisplayName("preflight from an origin that is not allowed is refused")
    void preflightFromUnknownOriginIsRefused() {
        int status = client.method(HttpMethod.OPTIONS).uri("/api/v1/echo/ping")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange().returnResult(String.class).getStatus().value();

        assertThat(status).isEqualTo(403);
    }
}
