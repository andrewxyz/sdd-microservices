package com.example.learnhub.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * The one wrapper every LearnHub event is published in, on the {@code learning.events} exchange.
 *
 * <p>Contract: {@code specs/001-platform-foundation/contracts/event-envelope.schema.json}, bundled at
 * {@code schemas/event-envelope.schema.json} on the classpath.
 *
 * <p>This type carries no domain knowledge on purpose (constitution Principle II): the payload stays an
 * opaque JSON object, and domain routing keys belong to the services that own those domains.
 *
 * @param eventId       unique per event; consumers use it for idempotency
 * @param type          {@code <domain>.<event>}, equal to the routing key it is published with
 * @param version       schema version of this event type, starting at 1
 * @param occurredAt    when the event happened, in UTC
 * @param correlationId correlation ID of the request or event that caused this one
 * @param payload       event-specific content, defined by the owning domain
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EventEnvelope(
        UUID eventId,
        String type,
        int version,
        Instant occurredAt,
        String correlationId,
        JsonNode payload) {

    public EventEnvelope {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (!RoutingKeys.isValid(type)) {
            throw new IllegalArgumentException(
                    "type must look like <domain>.<event>, for example payment.succeeded, but was: " + type);
        }
        if (version < 1) {
            throw new IllegalArgumentException("version starts at 1, but was: " + version);
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }
        if (correlationId == null) {
            throw new IllegalArgumentException("correlationId is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload is required; use an empty object rather than null");
        }
    }
}
