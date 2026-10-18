package com.example.learnhub.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import com.example.learnhub.enrollment.support.AbstractEnrollmentServiceIT;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/** US1 (FR-013), US6: a published {@code payment.succeeded} event results in exactly one enrollment. */
class PaymentSucceededConsumerIT extends AbstractEnrollmentServiceIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private EnrollmentRepository enrollments;

    private static EventEnvelope paymentSucceeded(UUID paymentId, UUID userId, UUID courseId) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("paymentId", paymentId.toString());
        payload.put("userId", userId.toString());
        payload.put("courseId", courseId.toString());
        payload.put("amountCents", 1999);
        payload.put("currency", "USD");
        payload.put("occurredAt", Instant.now().toString());
        return new EventEnvelope(UUID.randomUUID(), "payment.succeeded", 1, Instant.now(),
                UUID.randomUUID().toString(), payload);
    }

    @Test
    void publishedEventResultsInExactlyOneEnrollment() {
        UUID paymentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded",
                paymentSucceeded(paymentId, userId, courseId));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Enrollment enrollment = enrollments.findByUserIdAndCourseId(userId, courseId).orElseThrow();
            assertThat(enrollment.getSourcePaymentId()).isEqualTo(paymentId);
        });

        assertThat(enrollments.existsByUserIdAndCourseId(userId, courseId)).isTrue();
    }

    @Test
    void replayingTheSameEventIdNeverCreatesASecondEnrollment() {
        UUID paymentId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        EventEnvelope envelope = paymentSucceeded(paymentId, userId, courseId);

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded", envelope);
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> enrollments.existsByUserIdAndCourseId(userId, courseId));

        // Same eventId, sent again: must be ignored (research.md R6).
        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded", envelope);
        Awaitility.await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(enrollments.findByUserIdAndCourseId(userId, courseId)).isPresent());

        long count = enrollments.findAll().stream()
                .filter(e -> e.getUserId().equals(userId) && e.getCourseId().equals(courseId))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
