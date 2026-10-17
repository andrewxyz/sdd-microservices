package com.example.learnhub.common.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-016 and research R4: the watcher polls the config server, refreshes only when the settings
 * actually changed, and survives a config server outage by keeping the last good state.
 */
class ConfigWatcherTest {

    private static final String APPLICATION = "echo-service";
    private static final String PROFILES = "local";
    private static final String USER = "config-user";
    private static final String PASSWORD = "config-pass";

    private static final String BODY_ONE = """
            {"name":"echo-service","profiles":["local"],"version":"v1","state":null,
             "propertySources":[{"name":"echo-service.yml","source":{"echo.greeting":"first"}}]}
            """;
    private static final String BODY_TWO = """
            {"name":"echo-service","profiles":["local"],"version":"v2","state":null,
             "propertySources":[{"name":"echo-service.yml","source":{"echo.greeting":"second"}}]}
            """;
    /** Same settings as BODY_ONE, only the version differs: must NOT trigger a refresh. */
    private static final String BODY_ONE_NEW_VERSION = """
            {"name":"echo-service","profiles":["local"],"version":"v99","state":"x",
             "propertySources":[{"name":"echo-service.yml","source":{"echo.greeting":"first"}}]}
            """;

    private WireMockServer server;
    private AtomicInteger refreshes;
    private ConfigWatcher watcher;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        refreshes = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.stop();
        }
        server.stop();
    }

    private ConfigWatcherProperties properties() {
        ConfigWatcherProperties properties = new ConfigWatcherProperties();
        properties.setUri("http://localhost:" + server.port());
        properties.setUsername(USER);
        properties.setPassword(PASSWORD);
        properties.setInterval(Duration.ofMillis(100));
        return properties;
    }

    private void stubBody(String body) {
        server.stubFor(get(urlPathEqualTo("/" + APPLICATION + "/" + PROFILES))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private void startWatcher() {
        watcher = new ConfigWatcher(properties(), APPLICATION, PROFILES, refreshes::incrementAndGet);
        watcher.start();
    }

    private void awaitPolls(int count) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                server.verify(com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly(count),
                        getRequestedFor(urlPathEqualTo("/" + APPLICATION + "/" + PROFILES))));
    }

    @Test
    @DisplayName("the default poll interval is 5 seconds")
    void defaultIntervalIsFiveSeconds() {
        assertThat(new ConfigWatcherProperties().getInterval()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("polls GET /{application}/{profiles} with basic auth credentials")
    void pollsWithBasicAuth() {
        stubBody(BODY_ONE);
        startWatcher();
        awaitPolls(1);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((USER + ":" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.verify(getRequestedFor(urlPathEqualTo("/" + APPLICATION + "/" + PROFILES))
                .withHeader("Authorization", com.github.tomakehurst.wiremock.client.WireMock.equalTo(expected)));
    }

    @Test
    @DisplayName("the first poll records the hash without refreshing")
    void firstPollDoesNotRefresh() {
        stubBody(BODY_ONE);
        startWatcher();
        awaitPolls(3);

        assertThat(refreshes).hasValue(0);
    }

    @Test
    @DisplayName("an unchanged reply never refreshes, however many times it is polled")
    void unchangedNeverRefreshes() {
        stubBody(BODY_ONE);
        startWatcher();
        awaitPolls(5);

        assertThat(refreshes).hasValue(0);
    }

    @Test
    @DisplayName("a changed reply refreshes exactly once, not once per later poll")
    void changeRefreshesExactlyOnce() {
        stubBody(BODY_ONE);
        startWatcher();
        awaitPolls(2);

        stubBody(BODY_TWO);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> refreshes.get() == 1);
        awaitPolls(8);

        assertThat(refreshes).as("later polls see the same new value").hasValue(1);
    }

    @Test
    @DisplayName("a new version with unchanged settings does not refresh")
    void versionChangeAloneDoesNotRefresh() {
        stubBody(BODY_ONE);
        startWatcher();
        awaitPolls(2);

        stubBody(BODY_ONE_NEW_VERSION);
        awaitPolls(6);

        assertThat(refreshes).hasValue(0);
    }

    @Test
    @DisplayName("a server error keeps the last good state, does not refresh and does not kill the watcher")
    void serverErrorIsTolerated() {
        stubBody(BODY_ONE);
        startWatcher();
        awaitPolls(2);

        server.stubFor(get(urlPathEqualTo("/" + APPLICATION + "/" + PROFILES))
                .willReturn(aResponse().withStatus(500)));
        awaitPolls(5);
        assertThat(refreshes).as("an outage is not a config change").hasValue(0);

        stubBody(BODY_TWO);
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> refreshes.get() == 1);

        assertThat(refreshes).as("recovers and refreshes on the next real change").hasValue(1);
    }

    @Test
    @DisplayName("an unreachable config server is tolerated the same way")
    void connectionFailureIsTolerated() {
        stubBody(BODY_ONE);
        startWatcher();
        awaitPolls(2);

        server.stop();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(refreshes).hasValue(0);
        assertThat(watcher.isRunning()).as("watcher survives the outage").isTrue();
    }

    @Test
    @DisplayName("a slow reply cannot make two polls overlap")
    void pollsDoNotOverlap() {
        server.stubFor(get(urlPathEqualTo("/" + APPLICATION + "/" + PROFILES))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(400)
                        .withBody(BODY_ONE)));
        startWatcher();

        Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> watcher.maxConcurrentPolls() >= 1);
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(watcher.maxConcurrentPolls()).as("fixedDelay, not fixedRate").isEqualTo(1);
    }
}
