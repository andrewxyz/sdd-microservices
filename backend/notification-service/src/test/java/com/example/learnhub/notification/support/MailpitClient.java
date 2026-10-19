package com.example.learnhub.notification.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** A minimal client for Mailpit's own HTTP API (no new library — {@code java.net.http}, Principle X). */
public final class MailpitClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String baseUrl;

    public MailpitClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int messageCount() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/messages")).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(response.body());
            return root.get("total").asInt();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not query Mailpit", e);
        }
    }
}
