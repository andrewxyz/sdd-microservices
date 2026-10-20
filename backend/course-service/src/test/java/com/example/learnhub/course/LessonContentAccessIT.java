package com.example.learnhub.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.CourseStatus;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.infrastructure.LessonRepository;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import com.example.learnhub.course.support.EnrollmentServiceStub;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-024, US5 (003): only the owning instructor and administrators may ever read a lesson's content
 * through the editing view ({@code /full}). specs/004-checkout-enrollment FR-021 to FR-024 adds the new
 * reading view ({@code /content}), gated additionally by confirmed enrollment (research.md R5).
 */
class LessonContentAccessIT extends AbstractCourseServiceIT {

    private static final EnrollmentServiceStub ENROLLMENT_SERVICE = new EnrollmentServiceStub();

    static {
        ENROLLMENT_SERVICE.start();
    }

    @DynamicPropertySource
    static void enrollmentServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.enrollment-service.url", ENROLLMENT_SERVICE::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        ENROLLMENT_SERVICE.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private LessonRepository lessons;

    @Transactional
    Course seedWithSecretLesson(UUID instructorId) {
        Course course = new Course(UUID.randomUUID(), instructorId, Instant.now());
        course.setTitle("Private Content Course");
        course.setDescription("D");
        Course saved = courses.save(course);
        lessons.save(new Lesson(UUID.randomUUID(), saved, "Lesson", 0, 10, "TOP SECRET CONTENT"));
        return courses.findById(saved.getId()).orElseThrow();
    }

    @Test
    void ownerAndAdministratorSeeContentOthersAndAnonymousDoNot() throws Exception {
        UUID owner = UUID.randomUUID();
        Course course = seedWithSecretLesson(owner);

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/full")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons[0].content").value("TOP SECRET CONTENT"));

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/full")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .header(IdentityHeaders.USER_ROLE, "ADMINISTRATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons[0].content").value("TOP SECRET CONTENT"));

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/full")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/full")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .header(IdentityHeaders.USER_ROLE, "STUDENT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicDetailNeverContainsLessonContent() throws Exception {
        UUID owner = UUID.randomUUID();
        Course course = seedWithSecretLesson(owner);
        course.markPublished(Instant.now());
        courses.save(course);

        String body = mockMvc.perform(get("/api/v1/courses/" + course.getId()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("TOP SECRET CONTENT").doesNotContain("\"content\"");
    }

    @Test
    void enrolledStudentReadsFullContentThroughTheReadingView() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID student = UUID.randomUUID();
        Course course = seedWithSecretLesson(owner);
        course.markPublished(Instant.now());
        courses.save(course);
        ENROLLMENT_SERVICE.enrolled(course.getId(), student);
        UUID lessonId = lessons.findByCourseIdOrderByPosition(course.getId()).get(0).getId();

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/content")
                        .header(IdentityHeaders.USER_ID, student.toString())
                        .header(IdentityHeaders.USER_ROLE, "STUDENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons[0].id").value(lessonId.toString()))
                .andExpect(jsonPath("$.lessons[0].content").value("TOP SECRET CONTENT"));
    }

    @Test
    void nonEnrolledUserIsRefusedTheReadingView() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Course course = seedWithSecretLesson(owner);
        course.markPublished(Instant.now());
        courses.save(course);
        ENROLLMENT_SERVICE.notEnrolled(course.getId(), stranger);

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/content")
                        .header(IdentityHeaders.USER_ID, stranger.toString())
                        .header(IdentityHeaders.USER_ROLE, "STUDENT"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerAndAdministratorReadTheReadingViewRegardlessOfEnrollment() throws Exception {
        UUID owner = UUID.randomUUID();
        Course course = seedWithSecretLesson(owner);
        course.markPublished(Instant.now());
        courses.save(course);
        // Deliberately no enrollment stub for either caller: ownership/admin role must short-circuit
        // the enrollment check entirely (FR-023).

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/content")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons[0].content").value("TOP SECRET CONTENT"));

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/content")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .header(IdentityHeaders.USER_ROLE, "ADMINISTRATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons[0].content").value("TOP SECRET CONTENT"));
    }

    @Test
    void unconfirmedEnrollmentStatusRefusesContentRatherThanShowingIt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID student = UUID.randomUUID();
        Course course = seedWithSecretLesson(owner);
        course.markPublished(Instant.now());
        courses.save(course);
        ENROLLMENT_SERVICE.unavailable(course.getId(), student);

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/content")
                        .header(IdentityHeaders.USER_ID, student.toString())
                        .header(IdentityHeaders.USER_ROLE, "STUDENT"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unpublishedCourseIsNotFoundThroughTheReadingViewForANonOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Course course = seedWithSecretLesson(owner);
        assertThat(course.getStatus()).isEqualTo(CourseStatus.DRAFT);

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/content")
                        .header(IdentityHeaders.USER_ID, stranger.toString())
                        .header(IdentityHeaders.USER_ROLE, "STUDENT"))
                .andExpect(status().isNotFound());
    }
}
