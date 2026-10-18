package com.example.learnhub.payment.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;

/**
 * A WireMock stand-in for course-service's internal lookup
 * ({@code GET /api/v1/internal/courses/{courseId}}, specs/003-course-catalog), shared by every
 * payment-service IT that needs {@code CourseLookupClient} to see a specific course without a real
 * course-service running.
 */
public final class CourseServiceStub {

    private final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop();
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    public void published(UUID courseId, UUID instructorId, Integer priceCents) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(body(courseId, "PUBLISHED", priceCents, instructorId))));
    }

    public void notPublished(UUID courseId, UUID instructorId, String status) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(body(courseId, status, 0, instructorId))));
    }

    public void notFound(UUID courseId) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(404)));
    }

    private static String body(UUID courseId, String status, Integer priceCents, UUID instructorId) {
        return """
                {"courseId":"%s","status":"%s","priceCents":%s,"currency":"USD",
                 "instructorId":"%s","lessonCount":1}
                """.formatted(courseId, status, priceCents, instructorId);
    }
}
