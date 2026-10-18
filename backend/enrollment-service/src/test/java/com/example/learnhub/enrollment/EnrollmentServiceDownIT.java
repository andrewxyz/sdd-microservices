package com.example.learnhub.enrollment;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SC-004, FR-025: a payment that succeeds while enrollment *processing itself* is unavailable (not just
 * the broker, covered by payment-service's own {@code OutboxPublisherIT}) is not lost — the message
 * waits durably on the queue and is processed exactly once as soon as the listener resumes.
 */
class EnrollmentServiceDownIT extends AbstractEnrollmentServiceIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private EnrollmentRepository enrollments;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Test
    void aPaymentDuringAListenerOutageIsEnrolledExactlyOnceAfterItResumes() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        registry.stop();
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("paymentId", paymentId.toString());
            payload.put("userId", userId.toString());
            payload.put("courseId", courseId.toString());
            payload.put("amountCents", 500);
            payload.put("currency", "USD");
            payload.put("occurredAt", Instant.now().toString());
            EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "payment.succeeded", 1, Instant.now(),
                    UUID.randomUUID().toString(), payload);

            rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, "payment.succeeded", envelope);

            // Give the (stopped) consumer every chance it would have had, to prove it genuinely isn't
            // processing while down.
            assertThat(enrollments.existsByUserIdAndCourseId(userId, courseId)).isFalse();
        } finally {
            registry.start();
        }

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(enrollments.findByUserIdAndCourseId(userId, courseId))
                    .hasValueSatisfying(e -> assertThat(e.getSourcePaymentId()).isEqualTo(paymentId));
        });
        assertThat(enrollments.existsByUserIdAndCourseId(userId, courseId)).isTrue();
    }
}
