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
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** FR-001 to FR-005: the public catalog list — pagination, sorting, filtering and search. */
class CatalogListIT extends AbstractCourseServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private CourseRepository courses;

    @Autowired
    private LessonRepository lessons;

    private Category newCategory(String name) {
        return categories.save(new Category(UUID.randomUUID(), name));
    }

    @Transactional
    Course seedCourse(String title, CourseStatus status, UUID categoryId, int priceCents) {
        Course course = new Course(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        course.setTitle(title);
        course.setDescription("Description of " + title);
        course.setCategoryId(categoryId);
        course.setPriceCents(priceCents);
        Course saved = courses.save(course);
        lessons.save(new Lesson(UUID.randomUUID(), saved, "Lesson 1", 0, 10, "content"));
        if (status == CourseStatus.PUBLISHED) {
            saved.markPublished(Instant.now());
        } else if (status == CourseStatus.ARCHIVED) {
            saved.markPublished(Instant.now());
            saved.markArchived(Instant.now());
        }
        return courses.save(saved);
    }

    @Test
    void onlyPublishedCoursesAppearInTheCatalog() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Category category = newCategory("Cat-ListTest-" + tag);
        seedCourse("Draft " + tag, CourseStatus.DRAFT, category.getId(), 100);
        Course published = seedCourse("Published " + tag, CourseStatus.PUBLISHED, category.getId(), 100);
        seedCourse("Archived " + tag, CourseStatus.ARCHIVED, category.getId(), 100);

        String body = mockMvc.perform(get("/api/v1/courses").param("q", tag))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> titles = JsonPath.read(body, "$.items[*].title");
        assertThat(titles).containsExactly(published.getTitle());
    }

    @Test
    void sortingByPriceLowToHighAndHighToLow() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Category category = newCategory("Cat-Price-" + tag);
        seedCourse("Cheap " + tag, CourseStatus.PUBLISHED, category.getId(), 100);
        seedCourse("Mid " + tag, CourseStatus.PUBLISHED, category.getId(), 500);
        seedCourse("Pricey " + tag, CourseStatus.PUBLISHED, category.getId(), 900);

        String ascBody = mockMvc.perform(get("/api/v1/courses").param("q", tag).param("sort", "price_asc"))
                .andReturn().getResponse().getContentAsString();
        List<Integer> asc = JsonPath.read(ascBody, "$.items[*].priceCents");
        assertThat(asc).isSorted();

        String descBody = mockMvc.perform(get("/api/v1/courses").param("q", tag).param("sort", "price_desc"))
                .andReturn().getResponse().getContentAsString();
        List<Integer> desc = JsonPath.read(descBody, "$.items[*].priceCents");
        assertThat(desc).isSortedAccordingTo((a, b) -> b - a);
    }

    @Test
    void filterByCategoryLevelAndFree() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Category categoryA = newCategory("Cat-FilterA-" + tag);
        Category categoryB = newCategory("Cat-FilterB-" + tag);
        seedCourse("FreeInA " + tag, CourseStatus.PUBLISHED, categoryA.getId(), 0);
        seedCourse("PaidInA " + tag, CourseStatus.PUBLISHED, categoryA.getId(), 500);
        seedCourse("PaidInB " + tag, CourseStatus.PUBLISHED, categoryB.getId(), 500);

        String byCategory = mockMvc.perform(get("/api/v1/courses")
                        .param("q", tag).param("category", categoryA.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        List<String> inCategoryA = JsonPath.read(byCategory, "$.items[*].title");
        assertThat(inCategoryA).hasSize(2);

        String free = mockMvc.perform(get("/api/v1/courses").param("q", tag).param("free", "true"))
                .andReturn().getResponse().getContentAsString();
        List<String> freeTitles = JsonPath.read(free, "$.items[*].title");
        assertThat(freeTitles).containsExactly("FreeInA " + tag);
    }

    @Test
    void searchWithNoMatchesReturnsEmptyListNotError() throws Exception {
        mockMvc.perform(get("/api/v1/courses").param("q", "zzz-no-such-keyword-zzz-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void paginationCoversEveryPublishedCourseExactlyOnce() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Category category = newCategory("Cat-Page-" + tag);
        for (int i = 0; i < 5; i++) {
            seedCourse("Paged " + i + " " + tag, CourseStatus.PUBLISHED, category.getId(), 100);
        }

        String page0 = mockMvc.perform(get("/api/v1/courses").param("q", tag).param("page", "0").param("size", "3"))
                .andReturn().getResponse().getContentAsString();
        String page1 = mockMvc.perform(get("/api/v1/courses").param("q", tag).param("page", "1").param("size", "3"))
                .andReturn().getResponse().getContentAsString();

        List<String> firstPage = JsonPath.read(page0, "$.items[*].title");
        List<String> secondPage = JsonPath.read(page1, "$.items[*].title");
        assertThat(firstPage).hasSize(3);
        assertThat(secondPage).hasSize(2);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
    }
}
