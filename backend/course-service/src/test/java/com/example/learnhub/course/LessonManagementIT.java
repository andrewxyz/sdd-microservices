package com.example.learnhub.course;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.infrastructure.LessonRepository;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** FR-010, FR-011; spec.md Edge Cases: add/edit/remove/reorder a course's lessons. */
class LessonManagementIT extends AbstractCourseServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private LessonRepository lessons;

    private final UUID owner = UUID.randomUUID();

    @Transactional
    Course seedCourseWithOneLesson() {
        Course course = new Course(UUID.randomUUID(), owner, Instant.now());
        course.setTitle("Lesson course");
        course.setDescription("D");
        Course saved = courses.save(course);
        lessons.save(new Lesson(UUID.randomUUID(), saved, "First", 0, 10, "content 1"));
        return courses.findById(saved.getId()).orElseThrow();
    }

    @Transactional
    void addSecondLesson(Course course) {
        Course managed = courses.findById(course.getId()).orElseThrow();
        lessons.save(new Lesson(UUID.randomUUID(), managed, "Second", 1, 10, "content 2"));
    }

    private String etag(Course course) {
        return "\"" + course.getVersion() + "\"";
    }

    @Test
    void ownerAddsEditsAndRemovesALesson() throws Exception {
        Course course = seedCourseWithOneLesson();

        String addBody = "{\"title\":\"Second\",\"durationMinutes\":20,\"content\":\"content 2\"}";
        String addResponse = mockMvc.perform(post("/api/v1/courses/" + course.getId() + "/lessons")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(course))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1))
                .andReturn().getResponse().getContentAsString();
        String newLessonId = JsonPath.read(addResponse, "$.id");

        Course afterAdd = courses.findById(course.getId()).orElseThrow();
        String editBody = "{\"title\":\"Second Edited\",\"durationMinutes\":25,\"content\":\"edited\"}";
        mockMvc.perform(put("/api/v1/courses/" + course.getId() + "/lessons/" + newLessonId)
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(afterAdd))
                        .contentType(MediaType.APPLICATION_JSON).content(editBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Second Edited"));

        Course afterEdit = courses.findById(course.getId()).orElseThrow();
        mockMvc.perform(delete("/api/v1/courses/" + course.getId() + "/lessons/" + newLessonId)
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(afterEdit)))
                .andExpect(status().isNoContent());
    }

    @Test
    void reorderAppliesANewOrderButRejectsAnIncompleteList() throws Exception {
        Course course = seedCourseWithOneLesson();
        addSecondLesson(course);
        Course reloaded = courses.findById(course.getId()).orElseThrow();
        List<Lesson> ordered = lessons.findByCourseIdOrderByPosition(course.getId());
        UUID firstId = ordered.get(0).getId();
        UUID secondId = ordered.get(1).getId();

        String reversedOrder = "{\"lessonIdsInOrder\":[\"" + secondId + "\",\"" + firstId + "\"]}";
        mockMvc.perform(patch("/api/v1/courses/" + course.getId() + "/lessons/reorder")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(reloaded))
                        .contentType(MediaType.APPLICATION_JSON).content(reversedOrder))
                .andExpect(status().isOk());

        Course afterReorder = courses.findById(course.getId()).orElseThrow();
        String incompleteOrder = "{\"lessonIdsInOrder\":[\"" + firstId + "\"]}";
        mockMvc.perform(patch("/api/v1/courses/" + course.getId() + "/lessons/reorder")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(afterReorder))
                        .contentType(MediaType.APPLICATION_JSON).content(incompleteOrder))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aDifferentInstructorCannotManageLessons() throws Exception {
        Course course = seedCourseWithOneLesson();
        String addBody = "{\"title\":\"X\",\"durationMinutes\":5,\"content\":\"x\"}";

        mockMvc.perform(post("/api/v1/courses/" + course.getId() + "/lessons")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(course))
                        .contentType(MediaType.APPLICATION_JSON).content(addBody))
                .andExpect(status().isForbidden());
    }
}
