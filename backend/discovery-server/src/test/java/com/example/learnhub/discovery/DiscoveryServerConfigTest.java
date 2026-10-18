package com.example.learnhub.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * The registry's own settings must be local, not fetched from the config server: it starts first, so
 * there is nothing to fetch from yet (constitution Principle III).
 */
// A mock web environment, not NONE: the security configuration needs a web application context to
// build its HttpSecurity, so NONE would fail for reasons unrelated to what this test checks.
@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("local")
class DiscoveryServerConfigTest {

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("evicts dead instances quickly and never freezes the registry")
    void evictionTuning() {
        assertThat(environment.getProperty("eureka.server.eviction-interval-timer-in-ms")).isEqualTo("5000");
        assertThat(environment.getProperty("eureka.server.enable-self-preservation")).isEqualTo("false");
    }

    @Test
    @DisplayName("does not register with or fetch from itself")
    void doesNotRegisterWithItself() {
        assertThat(environment.getProperty("eureka.client.register-with-eureka")).isEqualTo("false");
        assertThat(environment.getProperty("eureka.client.fetch-registry")).isEqualTo("false");
    }

    @Test
    @DisplayName("listens on the port from the constitution's table")
    void port() {
        assertThat(environment.getProperty("server.port")).isEqualTo("9761");
    }

    @Test
    @DisplayName("the config watcher is off: this service has no central configuration")
    void configWatcherDisabled() {
        assertThat(environment.getProperty("platform.config-watcher.enabled")).isEqualTo("false");
    }

    @Test
    @DisplayName("no config server import, so startup does not depend on one")
    void noConfigImport() {
        assertThat(environment.getProperty("spring.config.import")).isNull();
    }

    @Test
    @DisplayName("liveness and readiness probes are enabled")
    void probesEnabled() {
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
    }
}
