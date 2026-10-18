package com.example.learnhub.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** FR-008 to FR-013: creating, editing, listing and deleting courses; ownership enforcement. */
class CourseManagementIT extends AbstractCourseServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private LessonRepository lessons;

    private static final String CREATE_BODY =
            "{\"title\":\"T\",\"description\":\"D\",\"categoryId\":null,\"priceCents\":null}";

    @Test
    void instructorCreatesADraftTheyOwn() throws Exception {
        UUID instructorId = UUID.randomUUID();
        String body = mockMvc.perform(post("/api/v1/courses")
                        .header(IdentityHeaders.USER_ID, instructorId.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.instructorId").value(instructorId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(body, "$.id")).isNotNull();
    }

    @Test
    void studentCannotCreateACourse() throws Exception {
        mockMvc.perform(post("/api/v1/courses")
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .header(IdentityHeaders.USER_ROLE, "STUDENT")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
    }

    @Transactional
    Course seedDraft(UUID instructorId) {
        Course course = new Course(UUID.randomUUID(), instructorId, Instant.now());
        course.setTitle("Seeded");
        course.setDescription("Seeded description");
        return courses.save(course);
    }

    @Test
    void ownerCanEditTheirCourseButAnotherInstructorCannot() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();
        Course course = seedDraft(owner);
        String editBody = "{\"title\":\"Edited\",\"description\":\"D2\",\"categoryId\":null,\"priceCents\":null}";

        mockMvc.perform(put("/api/v1/courses/" + course.getId())
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, "\"" + course.getVersion() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(editBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Edited"));

        mockMvc.perform(put("/api/v1/courses/" + course.getId())
                        .header(IdentityHeaders.USER_ID, someoneElse.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR")
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(editBody))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCanEditAnyonesCourse() throws Exception {
        Course course = seedDraft(UUID.randomUUID());
        String editBody = "{\"title\":\"By Admin\",\"description\":\"D\",\"categoryId\":null,\"priceCents\":null}";

        mockMvc.perform(put("/api/v1/courses/" + course.getId())
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .header(IdentityHeaders.USER_ROLE, "ADMINISTRATOR")
                        .header(HttpHeaders.IF_MATCH, "\"" + course.getVersion() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(editBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("By Admin"));
    }

    @Test
    void myCoursesListsOwnDraftsAndNeverAnotherInstructors() throws Exception {
        UUID owner = UUID.randomUUID();
        seedDraft(owner);
        seedDraft(owner);
        seedDraft(UUID.randomUUID());

        String body = mockMvc.perform(get("/api/v1/my/courses")
                        .header(IdentityHeaders.USER_ID, owner.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> statuses = JsonPath.read(body, "$[*].status");
        assertThat(statuses).hasSize(2).allMatch("DRAFT"::equals);
    }

    @Test
    void ownerDeletesTheirDraftButCannotDeleteAPublishedCourse() throws Exception {
        UUID owner = UUID.randomUUID();
        Course draft = seedDraft(owner);

        mockMvc.perform(delete("/api/v1/courses/" + draft.getId())
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR"))
                .andExpect(status().isNoContent());

        Category category = categories.save(new Category(UUID.randomUUID(), "Cat-Del-" + UUID.randomUUID()));
        Course published = new Course(UUID.randomUUID(), owner, Instant.now());
        published.setTitle("P");
        published.setDescription("D");
        published.setCategoryId(category.getId());
        published.setPriceCents(0);
        Course savedPublished = courses.save(published);
        lessons.save(new Lesson(UUID.randomUUID(), savedPublished, "L1", 0, 5, "c"));
        savedPublished.markPublished(Instant.now());
        courses.save(savedPublished);

        mockMvc.perform(delete("/api/v1/courses/" + savedPublished.getId())
                        .header(IdentityHeaders.USER_ID, owner.toString())
                        .header(IdentityHeaders.USER_ROLE, "INSTRUCTOR"))
                .andExpect(status().isConflict());
        assertThat(courses.findById(savedPublished.getId())).isPresent();
    }
}
