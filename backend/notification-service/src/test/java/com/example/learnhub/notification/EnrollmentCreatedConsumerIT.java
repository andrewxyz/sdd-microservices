package com.example.learnhub.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.notification.domain.Notification;
import com.example.learnhub.notification.domain.NotificationStatus;
import com.example.learnhub.notification.infrastructure.NotificationRepository;
import com.example.learnhub.notification.support.AbstractNotificationServiceIT;
import com.example.learnhub.notification.support.AccountServiceStub;
import com.example.learnhub.notification.support.CourseServiceStub;
import com.example.learnhub.notification.support.MailpitClient;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** US6 (FR-019, FR-022): a published {@code enrollment.created} event results in exactly one email. */
class EnrollmentCreatedConsumerIT extends AbstractNotificationServiceIT {

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

    @Autowired
    private NotificationRepository notifications;

    private final MailpitClient mailpit = new MailpitClient(mailpitApiBaseUrl());

    private static EventEnvelope enrollmentCreated(UUID userId, UUID courseId) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("enrollmentId", UUID.randomUUID().toString());
        payload.put("userId", userId.toString());
        payload.put("courseId", courseId.toString());
        payload.put("enrolledAt", Instant.now().toString());
        return new EventEnvelope(UUID.randomUUID(), LearningEvents.ENROLLMENT_CREATED, 1, Instant.now(),
                UUID.randomUUID().toString(), payload);
    }

    @Test
    void publishedEventResultsInExactlyOneEmailAndOneSentNotification() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        AUTH_SERVICE.registered(userId, "student-" + userId + "@example.com");
        COURSE_SERVICE.published(courseId, "Enrollment Email Course");
        int before = mailpit.messageCount();
        EventEnvelope envelope = enrollmentCreated(userId, courseId);

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.ENROLLMENT_CREATED, envelope);

        // 30s: this notification needs two Feign calls (auth-service then course-service), and whichever
        // runs first in this test class's context carries a one-time JIT/connection-pool "cold start"
        // tax on top of the actual network round trip.
        Awaitility.await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(mailpit.messageCount()).isEqualTo(before + 1);
            Notification notification = notifications.findByEventId(envelope.eventId()).orElseThrow();
            assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }

    @Test
    void replayingTheSameEventIdNeverSendsASecondEmail() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        AUTH_SERVICE.registered(userId, "student-" + userId + "@example.com");
        COURSE_SERVICE.published(courseId, "Replay Email Course");
        int before = mailpit.messageCount();
        EventEnvelope envelope = enrollmentCreated(userId, courseId);

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.ENROLLMENT_CREATED, envelope);
        // Wait for the row to actually reach SENT, not just the email count to rise: phase two (send) and
        // phase three (mark SENT) are two separate transactions, so a replay fired the instant the email
        // count increments could still land while the row is PENDING and trigger a genuine second send.
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> mailpit.messageCount() == before + 1
                && notifications.findByEventId(envelope.eventId())
                        .map(n -> n.getStatus() == NotificationStatus.SENT).orElse(false));

        // Same eventId, sent again: must never result in a second email (FR-022).
        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.ENROLLMENT_CREATED, envelope);
        Awaitility.await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(mailpit.messageCount()).isEqualTo(before + 1));
    }
}
