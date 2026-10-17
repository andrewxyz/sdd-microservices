package com.example.learnhub.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

/**
 * FR-018: settings are readable without a login only in local development. Elsewhere they are
 * credentials and connection strings, so they are closed off (constitution Principle VII).
 */
class ConfigServerSecurityTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles({"native", "local"})
    @DisplayName("local profile")
    class LocalProfile {

        @Autowired
        private TestRestTemplate rest;

        @Test
        @DisplayName("settings need no login")
        void settingsAreOpen() {
            assertThat(rest.getForEntity("/echo-service/local", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("health probes need no login")
        void probesAreOpen() {
            assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"CONFIG_USER=config-user", "CONFIG_PASSWORD=config-pass"})
    @ActiveProfiles({"native", "dev"})
    @DisplayName("non-local profile")
    class NonLocalProfile {

        @Autowired
        private TestRestTemplate rest;

        @Test
        @DisplayName("settings are refused without credentials")
        void settingsRefuseAnonymous() {
            assertThat(rest.getForEntity("/echo-service/dev", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("settings are served with the configured credentials")
        void settingsAcceptCredentials() {
            assertThat(rest.withBasicAuth("config-user", "config-pass")
                    .getForEntity("/echo-service/dev", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("wrong credentials are refused")
        void wrongCredentialsRefused() {
            assertThat(rest.withBasicAuth("config-user", "nope")
                    .getForEntity("/echo-service/dev", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("health probes stay open so container health checks work")
        void probesStayOpen() {
            assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }
}
