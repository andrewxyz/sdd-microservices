package com.example.learnhub.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import com.example.learnhub.enrollment.infrastructure.RabbitTopologyConfig;
import com.example.learnhub.enrollment.support.AbstractEnrollmentServiceIT;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * US6 (FR-026, FR-027): a message that cannot be processed, whether malformed or repeatedly failing, is
 * dead-lettered after a bounded number of attempts without blocking a valid message behind it
 * (research.md R6: three attempts, a stateless retry interceptor, a dedicated container concurrency
 * greater than one so one stuck thread cannot starve the rest).
 */
class DeadLetterIT extends AbstractEnrollmentServiceIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private EnrollmentRepository enrollments;

    private static EventEnvelope paymentSucceeded(ObjectNode payload) {
        return new EventEnvelope(UUID.randomUUID(), "payment.succeeded", 1, Instant.now(),
                UUID.randomUUID().toString(), payload);
    }

    @Test
    void aMalformedMessageDoesNotBlockAValidOneSentRightAfter() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message malformed = new Message("this is not valid json at all".getBytes(StandardCharsets.UTF_8), properties);
        rabbitTemplate.send(LearningEvents.EXCHANGE, "payment.succeeded", malformed);

        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode goodPayload = mapper.createObjectNode();
        goodPayload.put("paymentId", UUID.randomUUID().toString());
        goodPayload.put("userId", userId.toString());
        goodPayload.put("courseId", courseId.toString());
        goodPayload.put("amountCents", 500);
        goodPayload.put("currency", "USD");
        goodPayload.put("occurredAt", Instant.now().toString());
        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded", paymentSucceeded(goodPayload));

        // The valid message is processed quickly, not stuck behind the malformed one's retry cycle.
        Awaitility.await().atMost(Duration.ofSeconds(6))
                .until(() -> enrollments.existsByUserIdAndCourseId(userId, courseId));

        // The malformed message eventually exhausts its retries and lands on the DLQ.
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message onDlq = rabbitTemplate.receive(RabbitTopologyConfig.DEAD_LETTER_QUEUE, 500);
            assertThat(onDlq).as("the malformed message should have been dead-lettered").isNotNull();
        });
    }

    @Test
    void aMessageThatCannotBeParsedIntoThePayloadIsDeadLetteredAfterRetrying() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode badPayload = mapper.createObjectNode();
        badPayload.put("paymentId", UUID.randomUUID().toString());
        badPayload.put("userId", "not-a-valid-uuid");
        badPayload.put("courseId", UUID.randomUUID().toString());
        badPayload.put("amountCents", 500);
        badPayload.put("currency", "USD");
        badPayload.put("occurredAt", Instant.now().toString());

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded", paymentSucceeded(badPayload));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message onDlq = rabbitTemplate.receive(RabbitTopologyConfig.DEAD_LETTER_QUEUE, 500);
            assertThat(onDlq).as("a payload that never parses should have been dead-lettered").isNotNull();
        });
    }
}
