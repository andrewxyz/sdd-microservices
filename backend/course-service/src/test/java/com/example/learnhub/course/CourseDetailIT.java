package com.example.learnhub.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.course.domain.Category;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.CourseStatus;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.infrastructure.CategoryRepository;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.infrastructure.LessonRepository;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** FR-006, FR-007: the public single-course view, and identical "not found" for draft/archived/unknown. */
class CourseDetailIT extends AbstractCourseServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private LessonRepository lessons;

    @Transactional
    Course seed(CourseStatus status) {
        Category category = categories.save(new Category(UUID.randomUUID(), "Cat-Detail-" + UUID.randomUUID()));
        Course course = new Course(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        course.setTitle("Detail Course");
        course.setDescription("A course for detail tests");
        course.setCategoryId(category.getId());
        course.setPriceCents(1000);
        Course saved = courses.save(course);
        lessons.save(new Lesson(UUID.randomUUID(), saved, "Welcome", 0, 15, "secret content"));
        if (status == CourseStatus.PUBLISHED || status == CourseStatus.ARCHIVED) {
            saved.markPublished(Instant.now());
        }
        if (status == CourseStatus.ARCHIVED) {
            saved.markArchived(Instant.now());
        }
        return courses.save(saved);
    }

    @Test
    void publicViewShowsOutlineButNeverLessonContent() throws Exception {
        Course published = seed(CourseStatus.PUBLISHED);

        String body = mockMvc.perform(get("/api/v1/courses/" + published.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Detail Course"))
                .andExpect(jsonPath("$.lessons[0].title").value("Welcome"))
                .andExpect(jsonPath("$.lessons[0].durationMinutes").value(15))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret content").doesNotContain("\"content\"");
    }

    @Test
    void draftArchivedAndUnknownAllProduceTheIdenticalNotFound() throws Exception {
        Course draft = seed(CourseStatus.DRAFT);
        Course archived = seed(CourseStatus.ARCHIVED);
        UUID unknown = UUID.randomUUID();

        String draftBody = normalize(mockMvc.perform(get("/api/v1/courses/" + draft.getId()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());
        String archivedBody = normalize(mockMvc.perform(get("/api/v1/courses/" + archived.getId()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());
        String unknownBody = normalize(mockMvc.perform(get("/api/v1/courses/" + unknown))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString());

        assertThat(draftBody).isEqualTo(unknownBody);
        assertThat(archivedBody).isEqualTo(unknownBody);
    }

    private static String normalize(String problemDetailsJson) {
        return problemDetailsJson
                .replaceAll("\"instance\":\"[^\"]*\"", "")
                .replaceAll("\"correlationId\":\"[^\"]*\"", "");
    }
}
