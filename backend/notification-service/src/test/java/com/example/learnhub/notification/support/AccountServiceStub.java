package com.example.learnhub.notification.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.UUID;

/** A WireMock stand-in for auth-service's internal account lookup, shared by notification-service ITs. */
public final class AccountServiceStub {

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

    public void registered(UUID accountId, String email) {
        server.stubFor(get(urlEqualTo("/api/v1/internal/accounts/" + accountId))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"%s\",\"email\":\"%s\"}".formatted(accountId, email))));
    }
}
