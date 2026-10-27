package com.example.learnhub.analytics.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** A WireMock stand-in for course-service's two internal endpoints, shared by analytics-service ITs. */
public final class CourseServiceStub {

    private final WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop();
    }

    public void reset() {
        server.resetAll();
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    /** The instructor's courses as course-service lists them (newest first is the caller's concern). */
    public void owns(UUID instructorId, List<UUID> courseIds) {
        String items = courseIds.stream()
                .map(id -> "{\"courseId\":\"%s\",\"status\":\"PUBLISHED\",\"title\":\"Course %s\"}".formatted(id, id))
                .collect(Collectors.joining(","));
        server.stubFor(get(urlEqualTo("/api/v1/internal/courses?instructorId=" + instructorId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("[" + items + "]")));
    }

    /** The single-course lookup, which names the course's owner. */
    public void courseOwnedBy(UUID courseId, UUID instructorId) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"courseId":"%s","status":"PUBLISHED","priceCents":1999,"currency":"USD",
                                 "instructorId":"%s","lessonCount":1,"title":"T","lessonIds":[]}
                                """.formatted(courseId, instructorId))));
    }

    public void noSuchCourse(UUID courseId) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId)).willReturn(aResponse().withStatus(404)));
    }

    /** Every call fails with a server error, as if course-service were broken. */
    public void failEverything() {
        server.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/api/v1/internal/courses.*"))
                .willReturn(aResponse().withStatus(500)));
    }

    public int requestCount() {
        return server.getAllServeEvents().size();
    }
}
