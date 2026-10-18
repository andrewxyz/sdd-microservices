package com.example.learnhub.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.events.EventMapper;
import com.example.learnhub.payment.domain.PaymentSucceededPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Constitution Principle VI: "every event MUST have a serialization test." A fast unit test (no
 * Testcontainers), independent of {@code OutboxPublisherIT}'s integration-level check, mirroring
 * {@code shared-events}' own {@code EventEnvelopeSerializationTest} pattern.
 */
class PaymentSucceededPayloadSerializationTest {

    private final ObjectMapper mapper = EventMapper.create();

    @Test
    void roundTripsThroughJson() throws Exception {
        PaymentSucceededPayload original = new PaymentSucceededPayload(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2999, "USD",
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        String json = mapper.writeValueAsString(original);
        PaymentSucceededPayload roundTripped = mapper.readValue(json, PaymentSucceededPayload.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void matchesTheContractSchemaShape() throws Exception {
        PaymentSucceededPayload payload = new PaymentSucceededPayload(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, "USD", Instant.now());

        JsonNode tree = mapper.valueToTree(payload);

        assertThat(tree.has("paymentId")).isTrue();
        assertThat(tree.has("userId")).isTrue();
        assertThat(tree.has("courseId")).isTrue();
        assertThat(tree.has("amountCents")).isTrue();
        assertThat(tree.get("currency").asText()).isEqualTo("USD");
        assertThat(tree.has("occurredAt")).isTrue();
    }
}
