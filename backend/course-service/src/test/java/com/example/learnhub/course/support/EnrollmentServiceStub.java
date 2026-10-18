package com.example.learnhub.course.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;

/**
 * A WireMock stand-in for enrollment-service's internal lookup
 * ({@code GET /api/v1/internal/enrollments/{courseId}/{userId}}, specs/004-checkout-enrollment), shared
 * by course-service ITs that need {@code EnrollmentLookupClient} to see a specific enrollment status
 * without a real enrollment-service running.
 */
public final class EnrollmentServiceStub {

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

    public void enrolled(UUID courseId, UUID userId) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"enrolled\":true,\"enrolledAt\":\"2026-10-18T12:00:00Z\"}")));
    }

    public void notEnrolled(UUID courseId, UUID userId) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"enrolled\":false,\"enrolledAt\":null}")));
    }

    public void unavailable(UUID courseId, UUID userId) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .willReturn(aResponse().withStatus(500)));
    }
}
