package com.example.learnhub.analytics;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.analytics.infrastructure.CourseNotFoundException;
import com.example.learnhub.analytics.infrastructure.CourseOwner;
import com.example.learnhub.analytics.infrastructure.CourseOwnershipClient;
import com.example.learnhub.analytics.infrastructure.CourseOwnershipClientFallbackFactory;
import com.example.learnhub.analytics.infrastructure.InstructorCourseItem;
import com.example.learnhub.analytics.infrastructure.OwnershipUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
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
 * WireMock-stubbed contract test for analytics-service's {@link CourseOwnershipClient} (constitution
 * Principle VI): the list call and the detail call match course-service's internal contracts
 * (specs/005 detail, specs/009 list), and every failure becomes a domain exception.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = CourseOwnershipClientContractTest.TestConfig.class)
class CourseOwnershipClientContractTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class, RabbitAutoConfiguration.class})
    @EnableFeignClients(clients = CourseOwnershipClient.class)
    @Import(CourseOwnershipClientFallbackFactory.class)
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
    private CourseOwnershipClient client;

    @Test
    void listCallSendsTheInstructorIdAndMapsTheItems() {
        UUID instructor = UUID.randomUUID();
        UUID courseA = UUID.randomUUID();
        UUID courseB = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses?instructorId=" + instructor))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"courseId":"%s","status":"PUBLISHED","title":"A"},
                                 {"courseId":"%s","status":"DRAFT","title":"B"}]
                                """.formatted(courseA, courseB))));

        List<InstructorCourseItem> items = client.listByInstructor(instructor);

        assertThat(items).extracting(InstructorCourseItem::courseId).containsExactly(courseA, courseB);
        assertThat(items).extracting(InstructorCourseItem::status).containsExactly("PUBLISHED", "DRAFT");
    }

    @Test
    void detailCallReadsTheInstructorIdAndIgnoresTheRestOfTheLookup() {
        UUID courseId = UUID.randomUUID();
        UUID instructor = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"courseId":"%s","status":"PUBLISHED","priceCents":1999,"currency":"USD",
                                 "instructorId":"%s","lessonCount":2,"title":"T","lessonIds":["%s"]}
                                """.formatted(courseId, instructor, UUID.randomUUID()))));

        CourseOwner owner = client.detail(courseId);

        assertThat(owner.instructorId()).isEqualTo(instructor);
    }

    @Test
    void aNotFoundOnDetailMeansThereIsNoSuchCourse() {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.detail(courseId)).isInstanceOf(CourseNotFoundException.class);
    }

    @Test
    void aServerErrorOnTheListMeansOwnershipIsUnavailable() {
        UUID instructor = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses?instructorId=" + instructor))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.listByInstructor(instructor))
                .isInstanceOf(OwnershipUnavailableException.class);
    }

    @Test
    void aServerErrorOnDetailMeansOwnershipIsUnavailable() {
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.detail(courseId)).isInstanceOf(OwnershipUnavailableException.class);
    }

    @Test
    void aTimeoutMeansOwnershipIsUnavailable() {
        UUID instructor = UUID.randomUUID();
        COURSE_SERVICE.stubFor(get(urlEqualTo("/api/v1/internal/courses?instructorId=" + instructor))
                .willReturn(aResponse().withFixedDelay(20_000).withStatus(200)));

        assertThatThrownBy(() -> client.listByInstructor(instructor))
                .isInstanceOf(OwnershipUnavailableException.class);
    }
}
