package com.example.learnhub.gateway.refresh;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.gateway.ratelimit.RateLimiterProperties;
import com.example.learnhub.gateway.ratelimit.TokenBucketRateLimiter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * FR-024 live tuning: changing {@code platform.gateway.rate-limit.*} centrally and refreshing must
 * clear the buckets, so a raised limit applies at once rather than waiting for the old buckets to be
 * evicted (research R8).
 *
 * <p>{@link GatewayConfiguration} resets the limiter on a Spring Cloud event. {@link ContextRefresher}
 * was confirmed (via {@code javap} on spring-cloud-context) to publish only
 * {@code EnvironmentChangeEvent}, never {@code RefreshEvent}, so a listener on the wrong event type
 * would never fire and this test would fail with the bucket still empty after the limit is raised.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("ratelimitrefreshit")
class RateLimitRefreshIT {

    /** Must match spring.config.import in application-ratelimitrefreshit.yml. */
    private static final Path CONFIG = Path.of("target", "ratelimit-refresh-it", "config.yml");

    private static final String LOW_CAPACITY = """
            spring:
              cloud:
                gateway:
                  server:
                    webflux:
                      routes:
                        - id: probe
                          uri: forward:/actuator/health
                          predicates: ["Path=/__ratelimit-probe/**"]
                          metadata:
                            public: true
            platform:
              gateway:
                rate-limit:
                  capacity: 2
                  # Zero on purpose: a non-zero refill makes this assertion depend on how much wall
                  # time elapses between two HTTP round trips, which is not bounded in a test (JIT
                  # warmup, thread scheduling, slow I/O). With no refill, two tokens means exactly
                  # two requests succeed, deterministically, however long each one takes.
                  refill-per-second: 0
                  idle-eviction: PT10M
            """;

    private static final String HIGH_CAPACITY = """
            spring:
              cloud:
                gateway:
                  server:
                    webflux:
                      routes:
                        - id: probe
                          uri: forward:/actuator/health
                          predicates: ["Path=/__ratelimit-probe/**"]
                          metadata:
                            public: true
            platform:
              gateway:
                rate-limit:
                  capacity: 50
                  refill-per-second: 1
                  idle-eviction: PT10M
            """;

    @BeforeAll
    static void writeInitialConfig() throws Exception {
        Files.createDirectories(CONFIG.getParent());
        Files.writeString(CONFIG, LOW_CAPACITY);
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private ContextRefresher refresher;

    @Autowired
    private TokenBucketRateLimiter limiter;

    @Autowired
    private RateLimiterProperties properties;

    private int call() {
        return client.get().uri("/__ratelimit-probe/x").exchange()
                .returnResult(String.class).getStatus().value();
    }

    @Test
    @DisplayName("raising the limit centrally and refreshing clears the buckets at once")
    void refreshClearsBucketsSoARaisedLimitAppliesImmediately() throws Exception {
        assertThat(properties.getCapacity()).as("capacity must be bound from the imported file").isEqualTo(2);

        // Capacity 2, no refill: exactly two requests succeed, deterministically.
        assertThat(call()).as("first call under capacity 2").isEqualTo(200);
        assertThat(call()).as("second call under capacity 2").isEqualTo(200);
        assertThat(call()).as("third call exceeds capacity 2")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        Files.writeString(CONFIG, HIGH_CAPACITY, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        refresher.refresh();

        // If the reset listener never fires, the bucket for this client is still empty and every
        // call below keeps returning 429 until the slow 1-token/s refill catches up.
        assertThat(limiter.bucketCount())
                .as("refresh must drop the old buckets, not merely rebind the capacity number")
                .isZero();
        for (int i = 1; i <= 10; i++) {
            assertThat(call()).as("call %d after the limit was raised to 50", i).isEqualTo(200);
        }
    }
}
