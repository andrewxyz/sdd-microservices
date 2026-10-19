package com.example.learnhub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.notification.infrastructure.RabbitTopologyConfig;
import com.example.learnhub.notification.support.AbstractNotificationServiceIT;
import com.example.learnhub.notification.support.AccountServiceStub;
import com.example.learnhub.notification.support.CourseServiceStub;
import com.example.learnhub.notification.support.MailpitClient;
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
 * sent right after it, mirroring certificate-service's and 004's own {@code DeadLetterIT}.
 */
class DeadLetterIT extends AbstractNotificationServiceIT {

    private static final AccountServiceStub AUTH_SERVICE = new AccountServiceStub();
    private static final CourseServiceStub COURSE_SERVICE = new CourseServiceStub();

    static {
        AUTH_SERVICE.start();
        COURSE_SERVICE.start();
    }

    @DynamicPropertySource
    static void feignUrls(DynamicPropertyRegistry registry) {
        registry.add("auth-service.url", AUTH_SERVICE::baseUrl);
        registry.add("course-service.url", COURSE_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        AUTH_SERVICE.stop();
        COURSE_SERVICE.stop();
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private final MailpitClient mailpit = new MailpitClient(mailpitApiBaseUrl());

    private static EventEnvelope enrollmentCreated(ObjectNode payload) {
        return new EventEnvelope(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, 1, Instant.now(),
                UUID.randomUUID().toString(), payload);
    }

    @Test
    void aMalformedMessageDoesNotBlockAValidOneSentRightAfter() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        Message malformed = new Message("this is not valid json at all".getBytes(StandardCharsets.UTF_8), properties);
        rabbitTemplate.send(LearningEvents.EXCHANGE, LearningEvents.ENROLLMENT_CREATED, malformed);

        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        AUTH_SERVICE.registered(userId, "dlq-" + userId + "@example.com");
        COURSE_SERVICE.published(courseId, "Dead Letter Test Course");
        int before = mailpit.messageCount();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode goodPayload = mapper.createObjectNode();
        goodPayload.put("enrollmentId", UUID.randomUUID().toString());
        goodPayload.put("userId", userId.toString());
        goodPayload.put("courseId", courseId.toString());
        goodPayload.put("enrolledAt", Instant.now().toString());
        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.ENROLLMENT_CREATED,
                enrollmentCreated(goodPayload));

        // The valid message is processed on its own consumer thread, not stuck behind the malformed
        // one's retry cycle on another. 30s (not a tighter bound) to absorb the one-time JIT/connection
        // "cold start" tax the first auth-service/course-service Feign calls in this context can carry.
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> mailpit.messageCount() == before + 1);

        // The malformed message eventually exhausts its retries and lands on the DLQ.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
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
        badPayload.put("enrolledAt", Instant.now().toString());

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.ENROLLMENT_CREATED,
                enrollmentCreated(badPayload));

        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Message onDlq = rabbitTemplate.receive(RabbitTopologyConfig.DEAD_LETTER_QUEUE, 500);
            assertThat(onDlq).as("a payload that never parses should have been dead-lettered").isNotNull();
        });
    }
}
