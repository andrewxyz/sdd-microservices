package com.example.learnhub.course;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.course.domain.Category;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.domain.Lesson;
import com.example.learnhub.course.infrastructure.CategoryRepository;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.infrastructure.LessonRepository;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** FR-014 to FR-018: publishing, the completeness check, archiving, and republishing. */
class PublishArchiveIT extends AbstractCourseServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private LessonRepository lessons;

    private final UUID owner = UUID.randomUUID();

    @Transactional
    Course seedCompleteDraft() {
        Category category = categories.save(new Category(UUID.randomUUID(), "Cat-Pub-" + UUID.randomUUID()));
        Course course = new Course(UUID.randomUUID(), owner, Instant.now());
        course.setTitle("Complete");
        course.setDescription("D");
        course.setCategoryId(category.getId());
        course.setPriceCents(500);
        Course saved = courses.save(course);
        lessons.save(new Lesson(UUID.randomUUID(), saved, "L1", 0, 10, "c"));
        return courses.findById(saved.getId()).orElseThrow();
    }

    @Transactional
    Course seedIncompleteDraft() {
        Course course = new Course(UUID.randomUUID(), owner, Instant.now());
        course.setTitle("Incomplete");
        return courses.save(course);
    }

    private String etag(Course course) {
        return "\"" + course.getVersion() + "\"";
    }

    @Test
    void publishingACompleteDraftMakesItAppearInTheCatalog() throws Exception {
        Course course = seedCompleteDraft();

        mockMvc.perform(patch("/api/v1/courses/" + course.getId() + "/publish")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(course)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.publishedAt").exists());

        mockMvc.perform(get("/api/v1/courses/" + course.getId()))
                .andExpect(status().isOk());
    }

    @Test
    void publishingAnIncompleteDraftNamesEveryMissingPart() throws Exception {
        Course course = seedIncompleteDraft();

        mockMvc.perform(patch("/api/v1/courses/" + course.getId() + "/publish")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(course)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("description")))
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("category")))
                .andExpect(jsonPath("$.detail", org.hamcrest.Matchers.containsString("lesson")));
    }

    @Test
    void archivingHidesItFromTheCatalogButKeepsItIntactAndRepublishWorks() throws Exception {
        Course course = seedCompleteDraft();
        mockMvc.perform(patch("/api/v1/courses/" + course.getId() + "/publish")
                .header(IdentityHeaders.USER_ID, owner.toString())
                .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                .header(HttpHeaders.IF_MATCH, etag(course)));
        Course published = courses.findById(course.getId()).orElseThrow();

        mockMvc.perform(patch("/api/v1/courses/" + course.getId() + "/archive")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(published)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mockMvc.perform(get("/api/v1/courses/" + course.getId()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/courses/" + course.getId() + "/full")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lessons[0].title").value("L1"));

        Course archived = courses.findById(course.getId()).orElseThrow();
        mockMvc.perform(patch("/api/v1/courses/" + course.getId() + "/publish")
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(archived)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void nonOwnerCannotPublishOrArchive() throws Exception {
        Course course = seedCompleteDraft();

        mockMvc.perform(patch("/api/v1/courses/" + course.getId() + "/publish")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, etag(course)))
                .andExpect(status().isForbidden());
    }
}
