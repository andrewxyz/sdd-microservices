package com.example.learnhub.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.infrastructure.LessonRepository;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-024, US5: only the owning instructor and administrators may ever read a lesson's content. No new
 * implementation here — this proves a property {@code OwnershipGuard} and the public/full view split
 * (US1-US3) already establish, per spec.md's own "doesn't unlock new capability on its own."
 */
class LessonContentAccessIT extends AbstractCourseServiceIT {

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
}
