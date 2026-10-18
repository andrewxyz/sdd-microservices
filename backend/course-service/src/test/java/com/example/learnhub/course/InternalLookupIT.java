package com.example.learnhub.course;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.infrastructure.LessonRepository;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** FR-023: the internal lookup works for a course in any status, and 404s for an unknown id. */
class InternalLookupIT extends AbstractCourseServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private LessonRepository lessons;

    @Transactional
    Course seedDraftWithTwoLessons() {
        Course course = new Course(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        course.setPriceCents(1200);
        Course saved = courses.save(course);
        lessons.save(new Lesson(UUID.randomUUID(), saved, "L1", 0, 5, "c1"));
        lessons.save(new Lesson(UUID.randomUUID(), saved, "L2", 1, 5, "c2"));
        return saved;
    }

    @Test
    void looksUpACourseRegardlessOfStatus() throws Exception {
        Course course = seedDraftWithTwoLessons();

        mockMvc.perform(get("/api/v1/internal/courses/" + course.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.priceCents").value(1200))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.instructorId").value(course.getInstructorId().toString()))
                .andExpect(jsonPath("$.lessonCount").value(2));
    }

    @Test
    void unknownIdReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/internal/courses/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
