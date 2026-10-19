package com.example.learnhub.certificate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.certificate.infrastructure.CourseLookupClient;
import com.example.learnhub.certificate.infrastructure.CourseLookupClientFallbackFactory;
import com.example.learnhub.certificate.infrastructure.CourseLookupUnavailableException;
import com.example.learnhub.certificate.infrastructure.CourseSummaryResponse;
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

/**
 * WireMock-stubbed contract test for certificate-service's {@link CourseLookupClient} (research.md R5;
 * constitution Principle VI).
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
        registry.add("course-service.url", COURSE_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        COURSE_SERVICE.stop();
    }

    @Autowired
    private CourseLookupClient client;

    @Test
    void mapsA200ToACourseSummaryWithTitle() {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"courseId":"%s","status":"PUBLISHED","priceCents":1999,"currency":"USD",
                                 "instructorId":"%s","lessonCount":1,"title":"Certificate Contract Course",
                                 "lessonIds":[]}
                                """.formatted(courseId, UUID.randomUUID()))));

        CourseSummaryResponse summary = client.summary(courseId);

        assertThat(summary.title()).isEqualTo("Certificate Contract Course");
    }

    @Test
    void mapsA5xxToAThrownException() {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.summary(courseId))
                .isInstanceOf(CourseLookupUnavailableException.class);
    }

    @Test
    void mapsATimeoutToAThrownException() {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withFixedDelay(20_000).withStatus(200)));

        assertThatThrownBy(() -> client.summary(courseId))
                .isInstanceOf(CourseLookupUnavailableException.class);
    }
}
