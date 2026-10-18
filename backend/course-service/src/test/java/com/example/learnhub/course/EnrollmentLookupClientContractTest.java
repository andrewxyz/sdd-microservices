package com.example.learnhub.course;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.course.infrastructure.EnrollmentLookupClient;
import com.example.learnhub.course.infrastructure.EnrollmentLookupClientFallback;
import com.example.learnhub.course.infrastructure.EnrollmentLookupResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * WireMock-stubbed contract test for {@link EnrollmentLookupClient} (specs/004-checkout-enrollment
 * research.md R4; constitution Principle VI). No Postgres needed — a narrow context with
 * datasource/JPA/Flyway auto-configuration excluded, so this stays a fast unit test (no Testcontainers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = EnrollmentLookupClientContractTest.TestConfig.class)
class EnrollmentLookupClientContractTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    @EnableFeignClients(clients = EnrollmentLookupClient.class)
    @Import(EnrollmentLookupClientFallback.class)
    static class TestConfig {
    }

    private static final WireMockServer ENROLLMENT_SERVICE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        ENROLLMENT_SERVICE.start();
    }

    @DynamicPropertySource
    static void feignUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.enrollment-service.url", ENROLLMENT_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        ENROLLMENT_SERVICE.stop();
    }

    @Autowired
    private EnrollmentLookupClient client;

    @Test
    void mapsAnEnrolledResponse() {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"enrolled\":true,\"enrolledAt\":\"2026-10-18T12:00:00Z\"}")));

        EnrollmentLookupResponse response = client.lookup(courseId, userId);

        assertThat(response.enrolled()).isTrue();
        assertThat(response.enrolledAt()).isNotNull();
    }

    @Test
    void mapsANotEnrolledResponse() {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"enrolled\":false,\"enrolledAt\":null}")));

        EnrollmentLookupResponse response = client.lookup(courseId, userId);

        assertThat(response.enrolled()).isFalse();
        assertThat(response.enrolledAt()).isNull();
    }

    @Test
    void mapsA5xxToA503() {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.lookup(courseId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void mapsATimeoutToA503() {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .willReturn(aResponse().withFixedDelay(10_000).withStatus(200)));

        assertThatThrownBy(() -> client.lookup(courseId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }
}
