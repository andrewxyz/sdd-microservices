package com.example.learnhub.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** US1 (spec FR-001..FR-004, FR-009): the summary and the per-course list against a real database. */
class InstructorOverviewIT extends AbstractAnalyticsServiceIT {

    @Autowired
    private MockMvc mockMvc;

    private final UUID instructorId = UUID.randomUUID();

    private void row(UUID course, LocalDate day, long enrollments, long revenue, long completions, long certificates) {
        jdbc.update("INSERT INTO course_daily_stats VALUES (?, ?, ?, ?, ?, ?)", course, Date.valueOf(day),
                enrollments, revenue, completions, certificates);
    }

    private static MockHttpServletRequestBuilder asInstructor(MockHttpServletRequestBuilder request, UUID id) {
        return request.header("X-User-Id", id.toString()).header("X-User-Role", "INSTRUCTOR");
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "ADMINISTRATOR");
    }

    @Test
    void theSummaryCoversOnlyTheCallersOwnCourses() throws Exception {
        UUID mine1 = UUID.randomUUID();
        UUID mine2 = UUID.randomUUID();
        UUID mineQuiet = UUID.randomUUID();
        UUID someoneElses = UUID.randomUUID();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        row(mine1, today, 3, 5997, 1, 1);
        row(mine1, today.minusDays(1), 1, 1999, 0, 0);
        row(mine2, today, 2, 0, 1, 0);
        row(someoneElses, today, 50, 99999, 20, 20);
        COURSE_SERVICE.owns(instructorId, List.of(mine1, mine2, mineQuiet));

        mockMvc.perform(asInstructor(get("/api/v1/instructor/stats/summary"), instructorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("OWN"))
                .andExpect(jsonPath("$.totals.enrollments").value(6))
                .andExpect(jsonPath("$.totals.revenueCents").value(7996))
                .andExpect(jsonPath("$.totals.completions").value(2))
                .andExpect(jsonPath("$.totals.certificates").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void theListHoldsOnlyOwnedCoursesWithActivityAndEachRowSumsItsDays() throws Exception {
        UUID active = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        UUID someoneElses = UUID.randomUUID();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        row(active, today, 2, 4000, 1, 1);
        row(active, today.minusDays(5), 1, 2000, 0, 0);
        row(someoneElses, today, 9, 9, 9, 9);
        COURSE_SERVICE.owns(instructorId, List.of(active, quiet));

        mockMvc.perform(asInstructor(get("/api/v1/instructor/stats/courses"), instructorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].courseId").value(active.toString()))
                .andExpect(jsonPath("$.items[0].totals.enrollments").value(3))
                .andExpect(jsonPath("$.items[0].totals.revenueCents").value(6000))
                .andExpect(jsonPath("$.items[0].totals.completions").value(1))
                .andExpect(jsonPath("$.items[0].totals.certificates").value(1))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void anInstructorWithNoCoursesSeesZerosAndAnEmptyList() throws Exception {
        COURSE_SERVICE.owns(instructorId, List.of());

        mockMvc.perform(asInstructor(get("/api/v1/instructor/stats/summary"), instructorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.enrollments").value(0))
                .andExpect(jsonPath("$.totals.revenueCents").value(0));
        mockMvc.perform(asInstructor(get("/api/v1/instructor/stats/courses"), instructorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void twoHundredOwnedCoursesAreFlaggedAsTruncated() throws Exception {
        List<UUID> owned = IntStream.range(0, 200).mapToObj(i -> UUID.randomUUID()).toList();
        COURSE_SERVICE.owns(instructorId, owned);

        mockMvc.perform(asInstructor(get("/api/v1/instructor/stats/summary"), instructorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true));
        mockMvc.perform(asInstructor(get("/api/v1/instructor/stats/courses"), instructorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true));
    }

    @Test
    void anAdministratorSeesPlatformTotalsAndEveryCourseHighestRevenueFirst() throws Exception {
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        row(low, today, 1, 1000, 0, 0);
        row(high, today, 2, 9000, 1, 1);

        mockMvc.perform(asAdmin(get("/api/v1/instructor/stats/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("PLATFORM"))
                .andExpect(jsonPath("$.totals.revenueCents").value(10000))
                .andExpect(jsonPath("$.truncated").value(false));
        mockMvc.perform(asAdmin(get("/api/v1/instructor/stats/courses")).param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].courseId").value(high.toString()))
                .andExpect(jsonPath("$.items[1].courseId").value(low.toString()))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10));
        // No course-service call was needed for the administrator views.
        org.assertj.core.api.Assertions.assertThat(COURSE_SERVICE.requestCount()).isZero();
    }

    @Test
    void anOversizedOrNegativePageIsRejectedWithAFieldError() throws Exception {
        mockMvc.perform(asAdmin(get("/api/v1/instructor/stats/courses")).param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.size").exists())
                .andExpect(jsonPath("$.correlationId").exists());
        mockMvc.perform(asAdmin(get("/api/v1/instructor/stats/courses")).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.page").exists());
    }
}
