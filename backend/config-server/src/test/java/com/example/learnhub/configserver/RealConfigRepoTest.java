package com.example.learnhub.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Serves the real {@code backend/config-repo} and checks the shared defaults a new service relies on
 * are actually delivered. This is what makes FR-029 and SC-007 true in practice: if these keys ever
 * stop being served, a new service would silently lose registration, probes or structured logging.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.cloud.config.server.native.search-locations=file:../config-repo")
@ActiveProfiles({"native", "local"})
class RealConfigRepoTest {

    private static final Path CONFIG_REPO = Path.of("..", "config-repo");

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void repoIsPresent() {
        Assumptions.assumeTrue(Files.isDirectory(CONFIG_REPO), "backend/config-repo not present");
    }

    private String effective(String key) {
        JsonNode reply = rest.getForObject("/echo-service/local", JsonNode.class);
        for (JsonNode source : reply.get("propertySources")) {
            JsonNode value = source.path("source").get(key);
            if (value != null) {
                return value.asText();
            }
        }
        return null;
    }

    @Test
    @DisplayName("Eureka tuning from the shared defaults is delivered")
    void eurekaTuningDelivered() {
        assertThat(effective("eureka.client.registry-fetch-interval-seconds")).isEqualTo("5");
        assertThat(effective("eureka.instance.lease-renewal-interval-in-seconds")).isEqualTo("10");
        assertThat(effective("eureka.instance.lease-expiration-duration-in-seconds")).isEqualTo("30");
        assertThat(effective("eureka.client.healthcheck.enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("health probes and structured logging come from the shared defaults")
    void probesAndLoggingDelivered() {
        assertThat(effective("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(effective("logging.structured.format.console")).isEqualTo("ecs");
    }

    @Test
    @DisplayName("the local profile exposes the refresh endpoint for manual use")
    void localProfileExposesRefresh() {
        assertThat(effective("management.endpoints.web.exposure.include")).contains("refresh");
    }

    @Test
    @DisplayName("config clients fail fast rather than starting without settings")
    void failFastDelivered() {
        assertThat(effective("spring.cloud.config.fail-fast")).isEqualTo("true");
    }
}
