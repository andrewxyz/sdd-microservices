package com.example.learnhub.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.payment.infrastructure.CourseLookupClient;
import com.example.learnhub.payment.infrastructure.CourseLookupClientFallbackFactory;
import com.example.learnhub.payment.infrastructure.CourseSummaryResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
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
 * WireMock-stubbed contract test for {@link CourseLookupClient} (research.md R4; constitution
 * Principle VI's "every Feign client MUST have a contract test"). No Postgres/RabbitMQ needed — a
 * narrow Spring context with the datasource/JPA/Flyway/AMQP auto-configuration excluded, so this stays
 * a fast unit test (no Testcontainers), not an *IT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = CourseLookupClientContractTest.TestConfig.class)
class CourseLookupClientContractTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class, RabbitAutoConfiguration.class})
    @EnableFeignClients(clients = CourseLookupClient.class)
    @Import(CourseLookupClientFallbackFactory.class)
    static class TestConfig {
    }

    private static final WireMockServer COURSE_SERVICE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        COURSE_SERVICE.start();
    }

    @DynamicPropertySource
    static void feignUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.course-service.url", COURSE_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        COURSE_SERVICE.stop();
    }

    @Autowired
    private CourseLookupClient client;

    @Test
    void mapsA200ToACourseSummary() {
        UUID courseId = UUID.randomUUID();
        UUID instructorId = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"courseId":"%s","status":"PUBLISHED","priceCents":1999,"currency":"USD",
                                 "instructorId":"%s","lessonCount":3}
                                """.formatted(courseId, instructorId))));

        CourseSummaryResponse summary = client.summary(courseId);

        assertThat(summary.courseId()).isEqualTo(courseId);
        assertThat(summary.status()).isEqualTo("PUBLISHED");
        assertThat(summary.priceCents()).isEqualTo(1999);
        assertThat(summary.instructorId()).isEqualTo(instructorId);
        assertThat(summary.lessonCount()).isEqualTo(3);
    }

    @Test
    void mapsA404ToNull() {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(404)));

        assertThat(client.summary(courseId)).isNull();
    }

    @Test
    void mapsA5xxToA503() {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.summary(courseId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }

    @Test
    void mapsATimeoutToA503() {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withFixedDelay(10_000).withStatus(200)));

        assertThatThrownBy(() -> client.summary(courseId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503");
    }
}
