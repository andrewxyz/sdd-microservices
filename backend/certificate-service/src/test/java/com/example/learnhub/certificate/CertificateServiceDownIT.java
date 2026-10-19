package com.example.learnhub.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.certificate.infrastructure.CertificateRepository;
import com.example.learnhub.certificate.support.AbstractCertificateServiceIT;
import com.example.learnhub.certificate.support.CourseServiceStub;
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
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SC-004, FR-025: a course that completes while certificate-service's *listener* is unavailable (not just
 * the broker, covered by enrollment-service's own {@code OutboxPublisherIT}-style coverage) is not lost —
 * the message waits durably on the queue and results in exactly one certificate as soon as the listener
 * resumes (mirrors 004's own {@code EnrollmentServiceDownIT}).
 */
class CertificateServiceDownIT extends AbstractCertificateServiceIT {

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

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Test
    void aCourseCompletedDuringAListenerOutageIsCertifiedExactlyOnceAfterItResumes() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Outage Test Course");

        registry.stop();
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode payload = mapper.createObjectNode();
            payload.put("enrollmentId", UUID.randomUUID().toString());
            payload.put("userId", userId.toString());
            payload.put("courseId", courseId.toString());
            payload.put("completedAt", Instant.now().toString());
            EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), LearningEvents.COURSE_COMPLETED, 1,
                    Instant.now(), UUID.randomUUID().toString(), payload);

            rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.COURSE_COMPLETED, envelope);

            // Give the (stopped) consumer every chance it would have had, to prove it genuinely isn't
            // processing while down.
            assertThat(certificates.existsByUserIdAndCourseId(userId, courseId)).isFalse();
        } finally {
            registry.start();
        }

        // 20s, not 15s: this may be the first Feign call in the class's context, carrying a one-time
        // JIT/connection-pool "cold start" tax on top of the actual network round trip.
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> certificates.existsByUserIdAndCourseId(userId, courseId));

        long count = certificates.findAll().stream()
                .filter(c -> c.getUserId().equals(userId) && c.getCourseId().equals(courseId))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
