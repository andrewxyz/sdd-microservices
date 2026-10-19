package com.example.learnhub.enrollment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import com.example.learnhub.enrollment.support.AbstractEnrollmentServiceIT;
import com.example.learnhub.enrollment.support.CourseServiceStub;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** US1 (FR-001 to FR-007): mark/unmark a lesson complete and view progress. */
class LessonProgressIT extends AbstractEnrollmentServiceIT {

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

    private Enrollment seedEnrollment(UUID userId, UUID courseId) {
        return enrollments.save(new Enrollment(UUID.randomUUID(), userId, courseId, Instant.now(), UUID.randomUUID()));
    }

    @Test
    void markingALessonUpdatesProgress() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID lesson1 = UUID.randomUUID();
        UUID lesson2 = UUID.randomUUID();
        UUID lesson3 = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Progress Test Course", List.of(lesson1, lesson2, lesson3));
        seedEnrollment(userId, courseId);

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedLessons").value(1))
                .andExpect(jsonPath("$.totalLessons").value(3))
                .andExpect(jsonPath("$.percentComplete").value(33.333333333333336))
                .andExpect(jsonPath("$.courseCompletedAt").doesNotExist());
    }

    @Test
    void markingTheSameLessonTwiceChangesNothing() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID lesson1 = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Idempotent Mark Course", List.of(lesson1));
        seedEnrollment(userId, courseId);

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedLessons").value(1));

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedLessons").value(1));
    }

    @Test
    void unmarkingAnAlreadyIncompleteLessonChangesNothing() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID lesson1 = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Unmark Course", List.of(lesson1));
        seedEnrollment(userId, courseId);

        mockMvc.perform(delete("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedLessons").value(0));

        mockMvc.perform(delete("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedLessons").value(0));
    }

    @Test
    void unmarkingACompletedLessonRemovesItFromProgress() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID lesson1 = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Unmark After Complete Course", List.of(lesson1));
        seedEnrollment(userId, courseId);

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedLessons").value(1));

        mockMvc.perform(delete("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedLessons").value(0));
    }

    @Test
    void aNonEnrolledCallerIsRefused() throws Exception {
        UUID courseId = UUID.randomUUID();
        UUID lesson1 = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Not Enrolled Course", List.of(lesson1));

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/enrollments/" + courseId + "/progress")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aLessonNotBelongingToTheCourseIsRefused() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID realLesson = UUID.randomUUID();
        UUID foreignLesson = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "Foreign Lesson Course", List.of(realLesson));
        seedEnrollment(userId, courseId);

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + foreignLesson + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void viewingProgressReturnsCorrectCounts() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID lesson1 = UUID.randomUUID();
        UUID lesson2 = UUID.randomUUID();
        COURSE_SERVICE.published(courseId, "View Progress Course", List.of(lesson1, lesson2));
        seedEnrollment(userId, courseId);

        mockMvc.perform(put("/api/v1/enrollments/" + courseId + "/lessons/" + lesson1 + "/complete")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/enrollments/" + courseId + "/progress")
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedLessons").value(1))
                .andExpect(jsonPath("$.totalLessons").value(2))
                .andExpect(jsonPath("$.percentComplete").value(50.0));
    }
}
