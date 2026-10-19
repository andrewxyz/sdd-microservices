package com.example.learnhub.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import com.example.learnhub.enrollment.infrastructure.OutboxEventRepository;
import com.example.learnhub.enrollment.support.AbstractEnrollmentServiceIT;
import com.example.learnhub.enrollment.support.CourseServiceStub;
import com.example.learnhub.events.LearningEvents;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * US7 (FR-025, research.md R7): the broker, not just certificate-service's listener (covered by
 * certificate-service's own {@code CertificateServiceDownIT}), can be briefly unreachable — a
 * {@code course.completed} outbox row stays unpublished while the broker is paused and is published once
 * it recovers, mirroring payment-service's own {@code OutboxPublisherIT} (004).
 */
class OutboxPublisherIT extends AbstractEnrollmentServiceIT {

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
    private MockMvc mockMvc;

    @Autowired
    private EnrollmentRepository enrollments;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Test
    void aCourseThatCompletesDuringABrokerOutageIsPublishedOnceTheBrokerRecovers() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Broker Outage Test Course", List.of(lessonId));
        Enrollment enrollment = enrollments.save(
                new Enrollment(UUID.randomUUID(), userId, courseId, Instant.now(), UUID.randomUUID()));

        RABBITMQ.getDockerClient().pauseContainerCmd(RABBITMQ.getContainerId()).exec();
        try {
            mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lessonId + "/complete")
                            .header(IdentityHeaders.USER_ID, userId.toString()))
                    .andExpect(status().isOk());

            // The completion itself succeeds regardless (R7's whole point); give the publisher a couple
            // of poll cycles to prove it genuinely cannot get through while the broker is paused.
            Thread.sleep(4000);
            assertThat(outboxEvents.findByPublishedAtIsNull().stream()
                    .anyMatch(e -> e.getEnrollmentId().equals(enrollment.getId())
                            && e.getEventType().equals(LearningEvents.COURSE_COMPLETED)))
                    .isTrue();
        } finally {
            RABBITMQ.getDockerClient().unpauseContainerCmd(RABBITMQ.getContainerId()).exec();
        }

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(outboxEvents.findByPublishedAtIsNull().stream()
                        .anyMatch(e -> e.getEnrollmentId().equals(enrollment.getId())))
                        .isFalse());
    }
}
