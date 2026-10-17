package com.example.learnhub.echo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.Instant;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * FR-016 and SC-003: a changed central setting reaches a running service within ten seconds, with
 * no restart and no manual trigger.
 *
 * <p>WireMock plays the config server so the change can be made instantly and the clock measured
 * precisely. The watcher runs at its real five-second interval, so this measures the true path:
 * poll, notice the hash changed, refresh, rebind the properties.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EchoLiveRefreshIT {

    private static final String FIRST = body("first");
    private static final String SECOND = body("second");

    private static WireMockServer configServer;

    private static String body(String greeting) {
        return """
                {"name":"echo-service","profiles":["default"],"version":"v-%s","state":null,
                 "propertySources":[{"name":"echo-service.yml","source":{"echo.greeting":"%s"}}]}
                """.formatted(greeting, greeting);
    }

    private static void serve(String payload) {
        configServer.resetAll();
        configServer.stubFor(get(urlPathMatching("/echo-service/.*"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(payload)));
    }

    @BeforeAll
    static void startConfigServer() {
        configServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        configServer.start();
        serve(FIRST);
    }

    @AfterAll
    static void stopConfigServer() {
        configServer.stop();
    }

    @DynamicPropertySource
    static void pointAtWireMock(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + configServer.port();
        registry.add("spring.config.import", () -> "configserver:" + url);
        registry.add("spring.cloud.config.uri", () -> url);
        registry.add("platform.config-watcher.uri", () -> url);
        registry.add("eureka.client.enabled", () -> "false");
    }

    @Autowired
    private EchoProperties properties;

    @Test
    @DisplayName("a central change reaches the running service within 10 seconds")
    void changeArrivesWithinTenSeconds() {
        assertThat(properties.getGreeting()).as("the value the service started with").isEqualTo("first");

        Instant changedAt = Instant.now();
        serve(SECOND);

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(properties.getGreeting()).isEqualTo("second"));

        assertThat(Duration.between(changedAt, Instant.now()))
                .as("SC-003 allows ten seconds")
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a config server outage keeps the last good value and recovers afterwards")
    void outageKeepsLastGoodValueThenRecovers() {
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(properties.getGreeting()).isEqualTo("second"));

        // Every poll now fails. The service must keep serving the last good value, not blank it.
        configServer.resetAll();
        configServer.stubFor(get(urlPathMatching("/echo-service/.*"))
                .willReturn(aResponse().withStatus(500)));

        Awaitility.await().during(Duration.ofSeconds(7)).atMost(Duration.ofSeconds(9))
                .untilAsserted(() -> assertThat(properties.getGreeting()).isEqualTo("second"));

        serve(body("third"));

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(properties.getGreeting()).isEqualTo("third"));
    }
}
