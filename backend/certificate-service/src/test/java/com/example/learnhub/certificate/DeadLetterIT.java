package com.example.learnhub.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.certificate.infrastructure.CertificateRepository;
import com.example.learnhub.certificate.infrastructure.RabbitTopologyConfig;
import com.example.learnhub.certificate.support.AbstractCertificateServiceIT;
import com.example.learnhub.certificate.support.CourseServiceStub;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * US7 (FR-023, FR-024): a malformed message is retried and dead-lettered without blocking a valid message
 * sent right after it (research.md R6: three attempts, a stateless retry interceptor, a dedicated
 * container concurrency greater than one so one stuck thread cannot starve the rest; mirrors 004's own
 * {@code DeadLetterIT}).
 */
class DeadLetterIT extends AbstractCertificateServiceIT {

    private static final CourseServiceStub COURSE_SERVICE = new CourseServiceStub();

    static {
        COURSE_SERVICE.start();
    }

    @DynamicPropertySource
    static void courseServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("course-service.url", COURSE_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        COURSE_SERVICE.stop();
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private CertificateRepository certificates;

    private static EventEnvelope courseCompleted(ObjectNode payload) {
        return new EventEnvelope(UUID.randomUUID(), LearningEvents.COURSE_COMPLETED, 1, Instant.now(),
                UUID.randomUUID().toString(), payload);
    }

    @Test
    void aMalformedMessageDoesNotBlockAValidOneSentRightAfter() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message malformed = new Message("this is not valid json at all".getBytes(StandardCharsets.UTF_8), properties);
        rabbitTemplate.send(LearningEvents.EXCHANGE, LearningEvents.COURSE_COMPLETED, malformed);

        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Dead Letter Test Course");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode goodPayload = mapper.createObjectNode();
        goodPayload.put("enrollmentId", UUID.randomUUID().toString());
        goodPayload.put("userId", userId.toString());
        goodPayload.put("courseId", courseId.toString());
        goodPayload.put("completedAt", Instant.now().toString());
        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.COURSE_COMPLETED,
                courseCompleted(goodPayload));

        // The valid message is processed on its own consumer thread, not stuck behind the malformed
        // one's retry cycle on another. 20s (not a tighter bound) to absorb the one-time JIT/connection
        // "cold start" tax the very first Feign call in this test class's context can carry.
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> certificates.existsByUserIdAndCourseId(userId, courseId));

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
        badPayload.put("enrollmentId", UUID.randomUUID().toString());
        badPayload.put("userId", "not-a-valid-uuid");
        badPayload.put("courseId", UUID.randomUUID().toString());
        badPayload.put("completedAt", Instant.now().toString());

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.COURSE_COMPLETED,
                courseCompleted(badPayload));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message onDlq = rabbitTemplate.receive(RabbitTopologyConfig.DEAD_LETTER_QUEUE, 500);
            assertThat(onDlq).as("a payload that never parses should have been dead-lettered").isNotNull();
        });
    }
}
