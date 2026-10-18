package com.example.learnhub.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * FR-018 and constitution Principle VII: the registry needs a login outside the local profile, with
 * the single exemption that v1.1.0 grants health probes so container health checks keep working.
 */
class DiscoveryServerSecurityTest {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("local")
    @DisplayName("local profile")
    class LocalProfile {

        @Autowired
        private TestRestTemplate rest;

        @Test
        @DisplayName("the registry API needs no login")
        void registryIsOpen() {
            ResponseEntity<String> response = rest.getForEntity("/eureka/apps", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("health probes need no login")
        void probesAreOpen() {
            assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"EUREKA_USER=registry-user", "EUREKA_PASSWORD=registry-pass"})
    @ActiveProfiles("dev")
    @DisplayName("non-local profile")
    class NonLocalProfile {

        @Autowired
        private TestRestTemplate rest;

        @Test
        @DisplayName("the registry API is refused without credentials")
        void registryRefusesAnonymous() {
            ResponseEntity<String> response = rest.getForEntity("/eureka/apps", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("the registry API accepts the configured credentials")
        void registryAcceptsCredentials() {
            ResponseEntity<String> response = rest
                    .withBasicAuth("registry-user", "registry-pass")
                    .getForEntity("/eureka/apps", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("wrong credentials are refused")
        void wrongCredentialsRefused() {
            ResponseEntity<String> response = rest
                    .withBasicAuth("registry-user", "wrong")
                    .getForEntity("/eureka/apps", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("health probes stay open so container health checks work")
        void probesStayOpen() {
            assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }
}
