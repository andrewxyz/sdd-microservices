package com.example.learnhub.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.analytics.support.EventSamples;
import com.example.learnhub.events.EventMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Constitution VI (every event has a serialization test): analytics-service keeps its own copy of the
 * payment payload (Principle II), and {@code EventMapper} rejects unknown properties, so a drifting copy
 * would dead-letter every payment and leave revenue at zero. This test pins the copy to the published
 * schema, and checks the sample ({@link EventSamples#payment}) the ingest ITs publish.
 */
class PaymentSucceededPayloadSchemaTest {

    private static final Path SCHEMA = Path.of("..", "..", "specs", "004-checkout-enrollment", "contracts",
            "payment-succeeded.event.schema.json");

    private static final ObjectMapper MAPPER = EventMapper.create();

    @Test
    void theCopyDeclaresExactlyThePropertiesOfThePublishedSchema() throws Exception {
        JsonNode schema = MAPPER.readTree(Files.readString(SCHEMA));
        JsonNode payload = schema.has("properties") && schema.get("properties").has("payload")
                ? schema.get("properties").get("payload") : schema;
        JsonNode properties = payload.get("properties");
        assertThat(properties).as("the schema's payload properties").isNotNull();

        Set<String> expected = new HashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        Set<String> declared = new HashSet<>();
        for (RecordComponent component : PaymentSucceededPayload.class.getRecordComponents()) {
            declared.add(component.getName());
        }

        assertThat(declared).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void aSchemaValidSampleDeserializesWithTheStrictMapper() throws Exception {
        UUID courseId = UUID.randomUUID();

        PaymentSucceededPayload payload =
                MAPPER.treeToValue(EventSamples.payment(courseId, 1999), PaymentSucceededPayload.class);

        assertThat(payload.courseId()).isEqualTo(courseId);
        assertThat(payload.amountCents()).isEqualTo(1999);
        assertThat(payload.currency()).isEqualTo("USD");
    }
}
