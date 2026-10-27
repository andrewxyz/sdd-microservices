package com.example.learnhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** US3 (spec FR-008..FR-010): who may see what, and that "not yours" looks exactly like "not there". */
class AccessControlIT extends AbstractAnalyticsServiceIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private final UUID instructorA = UUID.randomUUID();
    private final UUID instructorB = UUID.randomUUID();

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID id, String role) {
        return request.header("X-User-Id", id.toString()).header("X-User-Role", role);
    }

    private void row(UUID course, long enrollments, long revenue) {
        jdbc.update("INSERT INTO course_daily_stats VALUES (?, ?, ?, ?, 0, 0)", course,
                Date.valueOf(LocalDate.now(ZoneOffset.UTC)), enrollments, revenue);
    }

    /** The body of a problem response without the parts that legitimately differ per request. */
    private ObjectNode comparable(String body) throws Exception {
        ObjectNode node = (ObjectNode) JSON.readTree(body);
        node.remove("correlationId");
        node.remove("instance");
        return node;
    }

    @Test
    void anotherInstructorsCourseLooksExactlyLikeACourseThatDoesNotExist() throws Exception {
        UUID someonesCourse = UUID.randomUUID();
        UUID unknownCourse = UUID.randomUUID();
        COURSE_SERVICE.courseOwnedBy(someonesCourse, instructorB);
        COURSE_SERVICE.noSuchCourse(unknownCourse);
        row(someonesCourse, 5, 5000);

        var foreign = mockMvc.perform(as(get("/api/v1/instructor/stats/courses/" + someonesCourse), instructorA, "INSTRUCTOR"))
                .andExpect(status().isNotFound()).andReturn().getResponse();
        var unknown = mockMvc.perform(as(get("/api/v1/instructor/stats/courses/" + unknownCourse), instructorA, "INSTRUCTOR"))
                .andExpect(status().isNotFound()).andReturn().getResponse();

        assertThat(comparable(foreign.getContentAsString())).isEqualTo(comparable(unknown.getContentAsString()));
        assertThat(foreign.getContentType()).isEqualTo(unknown.getContentType());
        assertThat(foreign.getContentAsString()).doesNotContain("5000");
    }

    @Test
    void anAdministratorCanOpenAnyExistingCourse() throws Exception {
        UUID someonesCourse = UUID.randomUUID();
        COURSE_SERVICE.courseOwnedBy(someonesCourse, instructorB);
        row(someonesCourse, 5, 5000);

        mockMvc.perform(as(get("/api/v1/instructor/stats/courses/" + someonesCourse), UUID.randomUUID(), "ADMINISTRATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.enrollments").value(5))
                .andExpect(jsonPath("$.totals.revenueCents").value(5000));
    }

    @Test
    void anAdministratorAskingForAMissingCourseGetsNotFound() throws Exception {
        UUID unknownCourse = UUID.randomUUID();
        COURSE_SERVICE.noSuchCourse(unknownCourse);

        mockMvc.perform(as(get("/api/v1/instructor/stats/courses/" + unknownCourse), UUID.randomUUID(), "ADMINISTRATOR"))
                .andExpect(status().isNotFound());
    }

    @Test
    void oneInstructorNeverSeesAnotherInstructorsFigures() throws Exception {
        UUID courseA = UUID.randomUUID();
        UUID courseB = UUID.randomUUID();
        row(courseA, 1, 1000);
        row(courseB, 7, 7777);
        COURSE_SERVICE.owns(instructorA, List.of(courseA));
        COURSE_SERVICE.owns(instructorB, List.of(courseB));

        String forA = mockMvc.perform(as(get("/api/v1/instructor/stats/courses"), instructorA, "INSTRUCTOR"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(as(get("/api/v1/instructor/stats/summary"), instructorA, "INSTRUCTOR"))
                .andExpect(jsonPath("$.totals.enrollments").value(1))
                .andExpect(jsonPath("$.totals.revenueCents").value(1000));

        assertThat(forA).contains(courseA.toString()).doesNotContain(courseB.toString()).doesNotContain("7777");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/instructor/stats/summary", "/api/v1/instructor/stats/courses",
            "/api/v1/instructor/stats/courses/00000000-0000-0000-0000-000000000001"})
    void aStudentIsRefusedOnEveryEndpointWithNoFigures(String path) throws Exception {
        String body = mockMvc.perform(as(get(path), UUID.randomUUID(), "STUDENT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode problem = JSON.readTree(body);
        assertThat(problem.has("totals")).isFalse();
        assertThat(problem.has("items")).isFalse();
        assertThat(COURSE_SERVICE.requestCount()).as("a refused caller must not trigger a course lookup").isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/instructor/stats/summary", "/api/v1/instructor/stats/courses"})
    void missingIdentityHeadersAreRefused(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isForbidden());
    }
}
