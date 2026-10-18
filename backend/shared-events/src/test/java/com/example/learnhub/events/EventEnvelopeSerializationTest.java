package com.example.learnhub.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-027: the envelope serializes to exactly the published contract and round-trips unchanged. */
class EventEnvelopeSerializationTest {

    private static final String SCHEMA_RESOURCE = "schemas/event-envelope.schema.json";
    private static final Path CONTRACT =
            Path.of("..", "..", "specs", "001-platform-foundation", "contracts", "event-envelope.schema.json");

    private static ObjectMapper mapper;
    private static JsonNode schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        mapper = EventMapper.create();
        try (InputStream in = EventEnvelopeSerializationTest.class.getClassLoader()
                .getResourceAsStream(SCHEMA_RESOURCE)) {
            assertThat(in).as("classpath resource " + SCHEMA_RESOURCE).isNotNull();
            schema = mapper.readTree(in);
        }
    }

    private static EventEnvelope sample() {
        ObjectNode payload = JsonNodeFactoryHolder.MAPPER.createObjectNode();
        payload.put("orderId", "abc-123");
        payload.put("amount", 4999);
        return new EventEnvelope(
                UUID.fromString("0b8f5c1e-3a42-4d1e-9c77-5a2d6e8f0b19"),
                "payment.succeeded",
                1,
                Instant.parse("2026-10-17T09:30:00Z"),
                "6f1c2b7e-9d3a-4c55-8a10-2f4e7d9b1a33",
                payload);
    }

    @Test
    @DisplayName("serialized envelope satisfies the published schema")
    void serializedFormSatisfiesSchema() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sample()));
        JsonSchemaCheck.assertValid(schema, json);
    }

    @Test
    @DisplayName("serialized field set is exactly the schema's properties, and all are required")
    void fieldSetMatchesSchema() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sample()));

        List<String> serialized = fieldNames(json);
        List<String> declared = fieldNames(schema.get("properties"));
        List<String> required = new java.util.ArrayList<>();
        schema.get("required").forEach(node -> required.add(node.asText()));

        assertThat(serialized).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(required).containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    @DisplayName("round trip preserves every field")
    void roundTripPreservesEveryField() throws Exception {
        EventEnvelope original = sample();

        String json = mapper.writeValueAsString(original);
        EventEnvelope restored = mapper.readValue(json, EventEnvelope.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.payload().get("orderId").asText()).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("occurredAt serializes as a UTC ISO-8601 instant ending in Z")
    void occurredAtIsUtcIso8601() throws Exception {
        JsonNode json = mapper.readTree(mapper.writeValueAsString(sample()));

        String occurredAt = json.get("occurredAt").asText();
        assertThat(occurredAt).isEqualTo("2026-10-17T09:30:00Z").endsWith("Z");
        assertThat(Instant.parse(occurredAt)).isEqualTo(Instant.parse("2026-10-17T09:30:00Z"));
    }

    @Test
    @DisplayName("unknown fields are rejected on deserialization")
    void unknownFieldsRejected() throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(mapper.writeValueAsString(sample()));
        json.put("sneakyExtra", "nope");

        assertThatThrownBy(() -> mapper.readValue(mapper.writeValueAsString(json), EventEnvelope.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }

    @Test
    @DisplayName("an envelope that breaks a schema rule is caught")
    void schemaCheckActuallyCatchesViolations() throws Exception {
        ObjectNode json = (ObjectNode) mapper.readTree(mapper.writeValueAsString(sample()));
        json.put("type", "Payment.Succeeded");
        json.put("version", 0);

        assertThatThrownBy(() -> JsonSchemaCheck.assertValid(schema, json))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("type")
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("the bundled schema is identical to the published contract")
    void bundledSchemaMatchesContract() throws Exception {
        Assumptions.assumeTrue(Files.exists(CONTRACT), "contract file not present in this checkout");

        JsonNode published = mapper.readTree(Files.readString(CONTRACT, StandardCharsets.UTF_8));

        assertThat(schema).isEqualTo(published);
    }

    @Test
    @DisplayName("truncating occurredAt to millis still round-trips")
    void instantPrecisionRoundTrips() throws Exception {
        EventEnvelope original = new EventEnvelope(
                UUID.randomUUID(), "course.published", 2,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), "corr-id-12345678",
                JsonNodeFactoryHolder.MAPPER.createObjectNode());

        EventEnvelope restored = mapper.readValue(mapper.writeValueAsString(original), EventEnvelope.class);

        assertThat(restored).isEqualTo(original);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    /** Holds a mapper for building payload nodes in test fixtures. */
    static final class JsonNodeFactoryHolder {
        static final ObjectMapper MAPPER = new ObjectMapper();

        private JsonNodeFactoryHolder() {
        }
    }
}
