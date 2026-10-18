package com.example.learnhub.gateway.ratelimit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.gateway.JsonSchemaCheck;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;

/**
 * FR-024 end to end: exceeding the limit returns 429 problem details with {@code Retry-After},
 * a different client address is unaffected, and the limit recovers after refill.
 *
 * <p>The real {@link RemoteAddressClientResolver} always sees the same loopback address inside a
 * single-host test, so two genuinely different source addresses cannot be produced here. Research R8
 * anticipated exactly this and called for an injectable resolver; {@link TestClientKeyConfig} swaps in
 * one keyed by a test-only header so client isolation is proven against the real gateway filter chain
 * rather than only at the unit level (where {@code TokenBucketRateLimiterTest} already covers it).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ratelimitfilterit")
@Import(RateLimitFilterIT.TestClientKeyConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitFilterIT {

    private static final String TEST_CLIENT_HEADER = "X-Test-Client-Id";
    private static final Path CONFIG = Path.of("target", "ratelimit-filter-it", "config.yml");

    private static WireMockServer upstream;
    private static JsonNode problemSchema;

    @BeforeAll
    static void startUpstreamAndWriteConfig() throws Exception {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        upstream.stubFor(get(urlPathEqualTo("/api/v1/limited/ping"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        Files.createDirectories(CONFIG.getParent());
        Files.writeString(CONFIG, """
                spring:
                  cloud:
                    gateway:
                      server:
                        webflux:
                          routes:
                            - id: limited
                              uri: http://localhost:%d
                              order: 0
                              predicates: ["Path=/api/v1/limited/**"]
                              metadata:
                                public: true
                platform:
                  gateway:
                    rate-limit:
                      capacity: 3
                      # Zero on purpose, like RateLimitRefreshIT: a non-zero refill makes the
                      # "exceeded" assertion depend on how long each HTTP round trip happens to take,
                      # which is not bounded in a test. With no refill, three tokens means exactly
                      # three requests succeed, however long each one takes.
                      refill-per-second: 0
                      idle-eviction: PT10M
                """.formatted(upstream.port()));

        try (InputStream in = RateLimitFilterIT.class.getClassLoader()
                .getResourceAsStream("schemas/problem-details.schema.json")) {
            problemSchema = new ObjectMapper().readTree(in);
        }
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop();
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private ContextRefresher refresher;

    @Autowired
    private TokenBucketRateLimiter limiter;

    private EntityExchangeResult<byte[]> call(String clientId) {
        return client.get().uri("/api/v1/limited/ping")
                .header(TEST_CLIENT_HEADER, clientId)
                .exchange().expectBody().returnResult();
    }

    @Test
    @Order(1)
    @DisplayName("exceeding the limit returns 429 problem details with Retry-After")
    void exceedingTheLimitReturns429WithRetryAfter() throws Exception {
        String clientId = "addr-over-limit";
        for (int i = 1; i <= 3; i++) {
            assertThat(call(clientId).getStatus().value()).as("call %d of 3 under capacity", i).isEqualTo(200);
        }

        EntityExchangeResult<byte[]> refused = call(clientId);

        assertThat(refused.getStatus().value()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(refused.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(refused.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("Retry-After tells the client when it may try again")
                .isNotBlank();

        JsonNode body = new ObjectMapper().readTree(refused.getResponseBodyContent());
        JsonSchemaCheck.assertValid(problemSchema, body);
        assertThat(body.get("status").asInt()).isEqualTo(429);
    }

    @Test
    @Order(2)
    @DisplayName("a different client address is unaffected by another one's limit")
    void anotherAddressIsUnaffected() {
        String exhausted = "addr-exhausted";
        for (int i = 0; i < 3; i++) {
            call(exhausted);
        }
        assertThat(call(exhausted).getStatus().value())
                .as("this address is now over its own limit").isEqualTo(429);

        assertThat(call("addr-fresh").getStatus().value())
                .as("a different address starts with its own full bucket").isEqualTo(200);
    }

    @Test
    @Order(3)
    @DisplayName("the limit recovers once tokens refill")
    void limitRecoversAfterRefill() throws Exception {
        // Runs last and switches the whole class to a recovery-friendly config. refresh() also
        // clears every bucket (GatewayConfiguration's EnvironmentChangeEvent listener), so earlier
        // tests' buckets do not interfere.
        Files.writeString(CONFIG, """
                spring:
                  cloud:
                    gateway:
                      server:
                        webflux:
                          routes:
                            - id: limited
                              uri: http://localhost:%d
                              order: 0
                              predicates: ["Path=/api/v1/limited/**"]
                              metadata:
                                public: true
                platform:
                  gateway:
                    rate-limit:
                      capacity: 1
                      refill-per-second: 10
                      idle-eviction: PT10M
                """.formatted(upstream.port()));
        refresher.refresh();

        String clientId = "addr-recovers";

        // Drained directly on the limiter, not over HTTP: a round trip's duration is not bounded on
        // a slow machine, and the point here is recovery timing, not how fast this machine routes a
        // request. This also proves the filter's resolver and a direct call key the same bucket.
        assertThat(limiter.tryConsume(clientId)).as("drain the single token directly").isTrue();
        assertThat(call(clientId).getStatus().value())
                .as("the real HTTP path sees the same bucket as exhausted").isEqualTo(429);

        Thread.sleep(150); // refill-per-second 10 restores a token in 100ms; 150ms leaves margin.

        assertThat(call(clientId).getStatus().value()).as("a token has refilled").isEqualTo(200);
    }

    @TestConfiguration
    static class TestClientKeyConfig {
        @Bean
        @Primary
        ClientAddressResolver testClientKeyResolver() {
            return (ServerWebExchange exchange) -> {
                String header = exchange.getRequest().getHeaders().getFirst(TEST_CLIENT_HEADER);
                return header != null ? header : new RemoteAddressClientResolver().resolve(exchange);
            };
        }
    }
}
