package com.example.learnhub.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.course.application.CategoryService;
import com.example.learnhub.course.domain.Category;
import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.infrastructure.CategoryRepository;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * FR-019 to FR-022, FR-025: category create/rename/delete, uniqueness, the in-use deletion guard, and
 * — analyze finding G1 — category changes are protected by the same concurrency guard as courses.
 */
class CategoryManagementIT extends AbstractCourseServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private CourseRepository courses;

    @Test
    void createWithAUniqueNameSucceedsAndADuplicateIsRefused() throws Exception {
        String name = "Design-" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name.toLowerCase() + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void renamingKeepsItsReferencingCoursesPointedAtTheSameCategory() {
        Category category = categoryService.create("Music-" + UUID.randomUUID());
        Course course = new Course(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        course.setCategoryId(category.getId());
        Course saved = courses.save(course);

        categoryService.rename(category.getId(), "Renamed Music", "\"" + category.getVersion() + "\"");

        Course reloaded = courses.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCategoryId()).isEqualTo(category.getId());
    }

    @Test
    void deletingAnUnusedCategorySucceedsButAnInUseOneIsRefused() throws Exception {
        Category unused = categoryService.create("Unused-" + UUID.randomUUID());
        mockMvc.perform(delete("/api/v1/categories/" + unused.getId())
                        .header(HttpHeaders.IF_MATCH, "\"" + unused.getVersion() + "\""))
                .andExpect(status().isNoContent());

        Category inUse = categoryService.create("InUse-" + UUID.randomUUID());
        Course course = new Course(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        course.setCategoryId(inUse.getId());
        courses.save(course);

        mockMvc.perform(delete("/api/v1/categories/" + inUse.getId())
                        .header(HttpHeaders.IF_MATCH, "\"" + inUse.getVersion() + "\""))
                .andExpect(status().isConflict());
        assertThat(categories.findById(inUse.getId())).isPresent();
    }

    @Test
    void renameOrDeleteWithAStaleIfMatchIsRefused() {
        Category category = categoryService.create("Stale-" + UUID.randomUUID());

        org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> categoryService.rename(category.getId(), "New Name", "\"999\""));
        org.junit.jupiter.api.Assertions.assertThrows(ResponseStatusException.class,
                () -> categoryService.delete(category.getId(), "\"999\""));
    }
}
