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
import org.springframework.test.context.ActiveProfiles;

/**
 * FR-016 and SC-003: a changed central setting reaches a running service within ten seconds, with
 * no restart and no manual trigger.
 *
 * <p>WireMock plays the config server so the change lands instantly and the clock can be measured
 * precisely. The watcher polls on its real schedule, so this exercises the whole path: poll, notice
 * the hash changed, refresh the context, rebind the properties.
 *
 * <p>The port is fixed (see application-liveconfig.yml) because the config import must come from a
 * file, and a file cannot reference a randomly chosen port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("liveconfig")
class EchoLiveRefreshIT {

    private static final int CONFIG_PORT = 18888;

    private static WireMockServer configServer;

    private static String body(String greeting) {
        return """
                {"name":"echo-service","profiles":["liveconfig"],"version":"v-%s","state":null,
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
        configServer = new WireMockServer(WireMockConfiguration.options().port(CONFIG_PORT));
        configServer.start();
        serve(body("first"));
    }

    @AfterAll
    static void stopConfigServer() {
        if (configServer != null) {
            configServer.stop();
        }
    }

    @Autowired
    private EchoProperties properties;

    /**
     * Both tests share one application context, so neither may assume it runs first. Each settles on
     * a known value before measuring.
     */
    private void settleOn(String greeting) {
        serve(body(greeting));
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(properties.getGreeting()).isEqualTo(greeting));
    }

    @Test
    @DisplayName("a central change reaches the running service within 10 seconds")
    void changeArrivesWithinTenSeconds() {
        settleOn("first");

        Instant changedAt = Instant.now();
        serve(body("second"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(properties.getGreeting()).isEqualTo("second"));

        assertThat(Duration.between(changedAt, Instant.now()))
                .as("SC-003 allows ten seconds")
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a config server outage keeps the last good value, then recovers")
    void outageKeepsLastGoodValueThenRecovers() {
        settleOn("before-the-outage");
        String lastGood = properties.getGreeting();

        // Every poll now fails. The service must keep serving the last good value, not blank it.
        configServer.resetAll();
        configServer.stubFor(get(urlPathMatching("/echo-service/.*"))
                .willReturn(aResponse().withStatus(500)));

        Awaitility.await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(properties.getGreeting()).isEqualTo(lastGood));

        serve(body("third"));

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(properties.getGreeting()).isEqualTo("third"));
    }
}
