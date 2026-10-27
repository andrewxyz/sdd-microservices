package com.example.learnhub.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.course.domain.Course;
import com.example.learnhub.course.infrastructure.CourseRepository;
import com.example.learnhub.course.support.AbstractCourseServiceIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

/**
 * Provider-side check (constitution VI): the real response of {@code GET /api/v1/internal/courses}
 * conforms to specs/009-instructor-dashboard/contracts/course-service-internal-list.openapi.yaml, the
 * contract analytics-service's client is tested against.
 */
class InternalCourseListContractTest extends AbstractCourseServiceIT {

    private static final Path CONTRACT = Path.of("..", "..", "specs", "009-instructor-dashboard", "contracts",
            "course-service-internal-list.openapi.yaml");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CourseRepository courses;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) throws Exception {
        try (var in = Files.newInputStream(CONTRACT)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> components = (Map<String, Object>) root.get("components");
            Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
            return (Map<String, Object>) schemas.get(name);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRealResponseMatchesTheContract() throws Exception {
        UUID instructor = UUID.randomUUID();
        Course draft = new Course(UUID.randomUUID(), instructor, Instant.parse("2026-01-01T00:00:00Z"));
        draft.setTitle("Contract draft");
        courses.save(draft);
        Course published = new Course(UUID.randomUUID(), instructor, Instant.parse("2026-01-02T00:00:00Z"));
        published.setTitle("Contract published");
        published.markPublished(Instant.parse("2026-01-03T00:00:00Z"));
        courses.save(published);

        String body = mockMvc.perform(get("/api/v1/internal/courses").param("instructorId", instructor.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> item = schema("InstructorCourseItem");
        Set<String> required = new HashSet<>((List<String>) item.get("required"));
        Map<String, Object> properties = (Map<String, Object>) item.get("properties");
        List<String> statuses = (List<String>) ((Map<String, Object>) properties.get("status")).get("enum");

        JsonNode array = new ObjectMapper().readTree(body);
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        for (JsonNode element : array) {
            Set<String> fields = new HashSet<>();
            element.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).as("fields of %s", element).containsAll(required);
            assertThat(properties.keySet()).as("no undeclared field in %s", element).containsAll(fields);
            assertThat(statuses).contains(element.get("status").asText());
            UUID.fromString(element.get("courseId").asText());
        }
    }

    @Test
    void theContractCapsTheListAtTwoHundred() throws Exception {
        try (var in = Files.newInputStream(CONTRACT)) {
            String yaml = new String(in.readAllBytes());
            assertThat(yaml).contains("maxItems: 200");
        }
    }
}
