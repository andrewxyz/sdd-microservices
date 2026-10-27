package com.example.learnhub.analytics.support;

import com.example.learnhub.events.EventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Payloads shaped exactly like the four events analytics-service consumes, so the integration tests publish
 * what the producers publish. The payment sample is checked against the published schema by
 * {@code PaymentSucceededPayloadSchemaTest}.
 */
public final class EventSamples {

    private static final ObjectMapper MAPPER = EventMapper.create();

    private EventSamples() {
    }

    public static ObjectNode payment(UUID courseId, int amountCents) {
        return payment(courseId, amountCents, UUID.randomUUID(), UUID.randomUUID());
    }

    public static ObjectNode payment(UUID courseId, int amountCents, UUID userId, UUID paymentId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("paymentId", paymentId.toString());
        node.put("userId", userId.toString());
        node.put("courseId", courseId.toString());
        node.put("amountCents", amountCents);
        node.put("currency", "USD");
        node.put("occurredAt", "2026-03-01T10:00:00Z");
        return node;
    }

    public static ObjectNode enrollmentCreated(UUID courseId) {
        return enrollmentCreated(courseId, UUID.randomUUID(), UUID.randomUUID());
    }

    public static ObjectNode enrollmentCreated(UUID courseId, UUID userId, UUID enrollmentId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("enrollmentId", enrollmentId.toString());
        node.put("userId", userId.toString());
        node.put("courseId", courseId.toString());
        node.put("enrolledAt", Instant.parse("2026-03-01T10:00:00Z").toString());
        return node;
    }

    public static ObjectNode courseCompleted(UUID courseId) {
        return courseCompleted(courseId, UUID.randomUUID(), UUID.randomUUID());
    }

    public static ObjectNode courseCompleted(UUID courseId, UUID userId, UUID enrollmentId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("enrollmentId", enrollmentId.toString());
        node.put("userId", userId.toString());
        node.put("courseId", courseId.toString());
        node.put("completedAt", Instant.parse("2026-03-01T10:00:00Z").toString());
        return node;
    }

    public static ObjectNode certificateIssued(UUID courseId) {
        return certificateIssued(courseId, UUID.randomUUID(), UUID.randomUUID());
    }

    public static ObjectNode certificateIssued(UUID courseId, UUID userId, UUID certificateId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("certificateId", certificateId.toString());
        node.put("userId", userId.toString());
        node.put("courseId", courseId.toString());
        node.put("verificationCode", "ABCDEF0123456789");
        node.put("issuedAt", Instant.parse("2026-03-01T10:00:00Z").toString());
        return node;
    }
}
