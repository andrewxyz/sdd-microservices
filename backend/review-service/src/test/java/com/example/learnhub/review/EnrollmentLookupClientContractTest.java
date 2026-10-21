package com.example.learnhub.review;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.review.infrastructure.EnrollmentLookupClient;
import com.example.learnhub.review.infrastructure.EnrollmentLookupClientFallback;
import com.example.learnhub.review.infrastructure.EnrollmentLookupResponse;
import com.example.learnhub.review.infrastructure.EnrollmentLookupUnavailableException;
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

/**
 * WireMock-stubbed contract test for review-service's {@link EnrollmentLookupClient} (research.md R2;
 * constitution Principle VI), mirroring course-service's own {@code EnrollmentLookupClientContractTest}.
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
    void mapsA5xxToAThrownUnavailableException() {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.lookup(courseId, userId))
                .isInstanceOf(EnrollmentLookupUnavailableException.class);
    }

    @Test
    void mapsATimeoutToAThrownUnavailableException() {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .willReturn(aResponse().withFixedDelay(20_000).withStatus(200)));

        assertThatThrownBy(() -> client.lookup(courseId, userId))
                .isInstanceOf(EnrollmentLookupUnavailableException.class);
    }
}
