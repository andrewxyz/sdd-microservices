package com.example.learnhub.notification.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;

/** A WireMock stand-in for course-service's internal lookup, shared by notification-service ITs. */
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

    public void published(UUID courseId, String title) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/courses/" + courseId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"courseId":"%s","status":"PUBLISHED","priceCents":0,"currency":"USD",
                                 "instructorId":"%s","lessonCount":1,"title":"%s","lessonIds":[]}
                                """.formatted(courseId, UUID.randomUUID(), title))));
    }
}
