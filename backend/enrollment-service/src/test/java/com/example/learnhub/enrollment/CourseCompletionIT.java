package com.example.learnhub.enrollment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import com.example.learnhub.enrollment.infrastructure.OutboxEventRepository;
import com.example.learnhub.enrollment.support.AbstractEnrollmentServiceIT;
import com.example.learnhub.enrollment.support.CourseServiceStub;
import com.example.learnhub.events.LearningEvents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** US2 (FR-008, FR-009): completing the last lesson sets the enrollment completed exactly once. */
class CourseCompletionIT extends AbstractEnrollmentServiceIT {

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

    private Enrollment seedEnrollment(UUID userId, UUID courseId) {
        return enrollments.save(new Enrollment(UUID.randomUUID(), userId, courseId, Instant.now(), UUID.randomUUID()));
    }

    @Test
    void completingTheLastLessonSetsCompletedAt() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID lesson1 = UUID.randomUUID();
        UUID lesson2 = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Completion Test Course", List.of(lesson1, lesson2));
        seedEnrollment(userId, courseId);

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseCompletedAt").doesNotExist());

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson2 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseCompletedAt").isNotEmpty());

        Enrollment reloaded = enrollments.findByUserIdAndCourseId(userId, courseId).orElseThrow();
        assertThat(reloaded.getCompletedAt()).isNotNull();

        long outboxRowsForThisEnrollment = outboxEvents.findAll().stream()
                .filter(e -> e.getEnrollmentId().equals(reloaded.getId())
                        && e.getEventType().equals(LearningEvents.COURSE_COMPLETED))
                .count();
        assertThat(outboxRowsForThisEnrollment).isEqualTo(1);
    }

    @Test
    void unmarkingAndRemarkingAfterCompletionNeverResetsTheCompletionFact() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID lesson1 = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Sticky Completion Course", List.of(lesson1));
        Enrollment enrollment = seedEnrollment(userId, courseId);

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk());

        Instant firstCompletedAt = enrollments.findByUserIdAndCourseId(userId, courseId).orElseThrow().getCompletedAt();
        assertThat(firstCompletedAt).isNotNull();

        mockMvc.perform(delete("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk());

        Instant secondCompletedAt = enrollments.findByUserIdAndCourseId(userId, courseId).orElseThrow().getCompletedAt();
        assertThat(secondCompletedAt).isEqualTo(firstCompletedAt);

        long outboxRowCount = outboxEvents.findAll().stream()
                .filter(e -> e.getEnrollmentId().equals(enrollment.getId())
                        && e.getEventType().equals(LearningEvents.COURSE_COMPLETED))
                .count();
        assertThat(outboxRowCount).isEqualTo(1);
    }

    @Test
    void tenConcurrentCompletionsOfTheLastLessonsProduceExactlyOneEvent() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        List<UUID> lessonIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lessonIds.add(UUID.randomUUID());
        }
        COURSE_SERVICE.published(courseId, "Concurrency Test Course", lessonIds);
        Enrollment enrollment = seedEnrollment(userId, courseId);

        int concurrency = lessonIds.size();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (UUID lessonId : lessonIds) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lessonId + "/complete")
                                .header(IdentityHeaders.USER_ID, userId.toString()));
                    } catch (Exception ignored) {
                        // Only the aggregate outcome matters below.
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() -> {
            Enrollment reloaded = enrollments.findByUserIdAndCourseId(userId, courseId).orElseThrow();
            assertThat(reloaded.getCompletedAt()).isNotNull();
        });

        long outboxRowCount = outboxEvents.findAll().stream()
                .filter(e -> e.getEnrollmentId().equals(enrollment.getId())
                        && e.getEventType().equals(LearningEvents.COURSE_COMPLETED))
                .count();
        assertThat(outboxRowCount).isEqualTo(1);
    }
}
