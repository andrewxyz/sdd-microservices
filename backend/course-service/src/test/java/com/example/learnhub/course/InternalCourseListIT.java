package com.example.learnhub.course;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec 009 (research R8): the internal list of one instructor's courses, used by analytics-service.
 * Reached only service-to-service; no gateway route targets it.
 */
class InternalCourseListIT extends AbstractCourseServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courses;

    private Course seed(UUID instructorId, String title, Instant createdAt) {
        Course course = new Course(UUID.randomUUID(), instructorId, createdAt);
        course.setTitle(title);
        course.setPriceCents(1000);
        return courses.save(course);
    }

    @Test
    void returnsOnlyThatInstructorsCoursesInEveryStatusNewestFirst() throws Exception {
        UUID instructor = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        Course oldest = seed(instructor, "Oldest draft", base);
        Course middle = seed(instructor, "Middle published", base.plusSeconds(60));
        middle.markPublished(base.plusSeconds(120));
        courses.save(middle);
        Course newest = seed(instructor, "Newest archived", base.plusSeconds(180));
        newest.markPublished(base.plusSeconds(200));
        newest.markArchived(base.plusSeconds(210));
        courses.save(newest);
        seed(other, "Someone else's course", base.plusSeconds(300));

        mockMvc.perform(get("/api/v1/internal/courses").param("instructorId", instructor.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].courseId").value(newest.getId().toString()))
                .andExpect(jsonPath("$[0].status").value("ARCHIVED"))
                .andExpect(jsonPath("$[0].title").value("Newest archived"))
                .andExpect(jsonPath("$[1].courseId").value(middle.getId().toString()))
                .andExpect(jsonPath("$[1].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[2].courseId").value(oldest.getId().toString()))
                .andExpect(jsonPath("$[2].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].priceCents").doesNotExist())
                .andExpect(jsonPath("$[0].instructorId").doesNotExist());
    }

    @Test
    void returnsAtMostTwoHundredCourses() throws Exception {
        UUID instructor = UUID.randomUUID();
        Instant base = Instant.parse("2026-02-01T00:00:00Z");
        List<Course> many = new ArrayList<>();
        for (int i = 0; i < 205; i++) {
            Course course = new Course(UUID.randomUUID(), instructor, base.plusSeconds(i));
            course.setTitle("Course " + i);
            many.add(course);
        }
        courses.saveAll(many);

        mockMvc.perform(get("/api/v1/internal/courses").param("instructorId", instructor.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(200))
                .andExpect(jsonPath("$[0].title").value("Course 204"));
    }

    @Test
    void anInstructorWithNoCoursesGetsAnEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/internal/courses").param("instructorId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aMissingInstructorIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/internal/courses"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aMalformedInstructorIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/internal/courses").param("instructorId", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
