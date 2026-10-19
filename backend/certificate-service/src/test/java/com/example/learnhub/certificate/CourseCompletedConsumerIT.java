package com.example.learnhub.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.learnhub.certificate.domain.Certificate;
import com.example.learnhub.certificate.infrastructure.CertificateRepository;
import com.example.learnhub.certificate.support.AbstractCertificateServiceIT;
import com.example.learnhub.certificate.support.CourseServiceStub;
import com.example.learnhub.events.EventEnvelope;
import com.example.learnhub.events.LearningEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** US3 (FR-010, FR-011): a published {@code course.completed} event results in exactly one certificate. */
class CourseCompletedConsumerIT extends AbstractCertificateServiceIT {

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

    private static EventEnvelope courseCompleted(UUID userId, UUID courseId) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("enrollmentId", UUID.randomUUID().toString());
        payload.put("userId", userId.toString());
        payload.put("courseId", courseId.toString());
        payload.put("completedAt", Instant.now().toString());
        return new EventEnvelope(UUID.randomUUID(), LearningEvents.COURSE_COMPLETED, 1, Instant.now(),
                UUID.randomUUID().toString(), payload);
    }

    @Test
    void publishedEventResultsInExactlyOneCertificateWithTitleAndCode() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Consumer Contract Course");

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.COURSE_COMPLETED,
                courseCompleted(userId, courseId));

        // 20s, not the usual 10s: the very first Feign call through a freshly-built circuit breaker in
        // this test class's context carries a one-time JIT/connection-pool "cold start" tax (observed
        // ~5s locally, more under load) on top of the actual network round trip.
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            // Not .orElseThrow(): Awaitility's untilAsserted only retries on AssertionError, so a plain
            // NoSuchElementException from an empty Optional on an early poll would fail the test
            // immediately instead of waiting out the full budget.
            List<Certificate> matches = certificates.findAll().stream()
                    .filter(c -> c.getUserId().equals(userId) && c.getCourseId().equals(courseId))
                    .collect(Collectors.toList());
            assertThat(matches).hasSize(1);
            Certificate certificate = matches.get(0);
            assertThat(certificate.getVerificationCode()).hasSize(16);
            assertThat(certificate.getCourseTitle()).isEqualTo("Consumer Contract Course");
            assertThat(certificate.getIssuedAt()).isNotNull();
        });

        long count = certificates.findAll().stream()
                .filter(c -> c.getUserId().equals(userId) && c.getCourseId().equals(courseId))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void replayingTheSameEventIdNeverCreatesASecondCertificate() {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Replay Contract Course");
        EventEnvelope envelope = courseCompleted(userId, courseId);

        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.COURSE_COMPLETED, envelope);
        // 20s: this may be the first Feign call in the class's context if JUnit runs this test first
        // (see the cold-start note in the sibling test above).
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> certificates.existsByUserIdAndCourseId(userId, courseId));

        // Same eventId, sent again: must be ignored (research.md R6).
        rabbitTemplate.convertAndSend(LearningEvents.EXCHANGE, LearningEvents.COURSE_COMPLETED, envelope);
        Awaitility.await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(certificates.existsByUserIdAndCourseId(userId, courseId)).isTrue());

        long count = certificates.findAll().stream()
                .filter(c -> c.getUserId().equals(userId) && c.getCourseId().equals(courseId))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
