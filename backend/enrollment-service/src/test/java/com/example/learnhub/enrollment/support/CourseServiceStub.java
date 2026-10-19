package com.example.learnhub.enrollment.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A WireMock stand-in for course-service's internal lookup
 * ({@code GET /api/v1/internal/courses/{courseId}}, extended in
 * specs/005-progress-certificates-notifications research.md R3), shared by enrollment-service ITs that
 * need {@code CourseLookupClient} to see a specific course's title and lesson ids.
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

    public void published(UUID courseId, String title, List<UUID> lessonIds) {
        String lessonIdsJson = lessonIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
        server.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"courseId":"%s","status":"PUBLISHED","priceCents":0,"currency":"USD",
                                 "instructorId":"%s","lessonCount":%d,"title":"%s","lessonIds":[%s]}
                                """.formatted(courseId, UUID.randomUUID(), lessonIds.size(), title, lessonIdsJson))));
    }

    public void unavailable(UUID courseId) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(500)));
    }
}
