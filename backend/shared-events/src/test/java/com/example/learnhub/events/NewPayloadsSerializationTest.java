package com.example.learnhub.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Constitution Principle VI: "every event MUST have a serialization test." Fast unit tests (no
 * Testcontainers) for the three new payloads this feature adds, mirroring 004's own
 * {@code PaymentSucceededPayloadSerializationTest} pattern.
 */
class NewPayloadsSerializationTest {

    private final ObjectMapper mapper = EventMapper.create();

    @Test
    void courseCompletedPayloadRoundTripsThroughJson() throws Exception {
        CourseCompletedPayload original = new CourseCompletedPayload(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        String json = mapper.writeValueAsString(original);
        CourseCompletedPayload roundTripped = mapper.readValue(json, CourseCompletedPayload.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void courseCompletedPayloadMatchesTheContractSchemaShape() {
        CourseCompletedPayload payload = new CourseCompletedPayload(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        JsonNode tree = mapper.valueToTree(payload);

        assertThat(tree.has("enrollmentId")).isTrue();
        assertThat(tree.has("userId")).isTrue();
        assertThat(tree.has("courseId")).isTrue();
        assertThat(tree.has("completedAt")).isTrue();
    }

    @Test
    void certificateIssuedPayloadRoundTripsThroughJson() throws Exception {
        CertificateIssuedPayload original = new CertificateIssuedPayload(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "7K9M4PQRS2N8X3WT",
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        String json = mapper.writeValueAsString(original);
        CertificateIssuedPayload roundTripped = mapper.readValue(json, CertificateIssuedPayload.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void certificateIssuedPayloadMatchesTheContractSchemaShape() {
        CertificateIssuedPayload payload = new CertificateIssuedPayload(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ABCD1234EFGH5678", Instant.now());

        JsonNode tree = mapper.valueToTree(payload);

        assertThat(tree.has("certificateId")).isTrue();
        assertThat(tree.has("userId")).isTrue();
        assertThat(tree.has("courseId")).isTrue();
        assertThat(tree.get("verificationCode").asText()).isEqualTo("ABCD1234EFGH5678");
        assertThat(tree.has("issuedAt")).isTrue();
        assertThat(tree.has("courseTitle")).isFalse();
    }

    @Test
    void enrollmentCreatedPayloadRoundTripsThroughJson() throws Exception {
        EnrollmentCreatedPayload original = new EnrollmentCreatedPayload(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));

        String json = mapper.writeValueAsString(original);
        EnrollmentCreatedPayload roundTripped = mapper.readValue(json, EnrollmentCreatedPayload.class);

        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void enrollmentCreatedPayloadMatchesTheContractSchemaShape() {
        EnrollmentCreatedPayload payload = new EnrollmentCreatedPayload(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        JsonNode tree = mapper.valueToTree(payload);

        assertThat(tree.has("enrollmentId")).isTrue();
        assertThat(tree.has("userId")).isTrue();
        assertThat(tree.has("courseId")).isTrue();
        assertThat(tree.has("enrolledAt")).isTrue();
    }
}
