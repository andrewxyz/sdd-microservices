package com.example.learnhub.analytics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** US2 (spec FR-005, FR-006, FR-018): one course's totals, funnel and daily series. */
class InstructorCourseDetailIT extends AbstractAnalyticsServiceIT {

    @Autowired
    private MockMvc mockMvc;

    private final UUID instructorId = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();

    private void row(LocalDate day, long enrollments, long revenue, long completions, long certificates) {
        jdbc.update("INSERT INTO course_daily_stats VALUES (?, ?, ?, ?, ?, ?)", courseId, Date.valueOf(day),
                enrollments, revenue, completions, certificates);
    }

    private MockHttpServletRequestBuilder asOwner(String days) {
        MockHttpServletRequestBuilder request = get("/api/v1/instructor/stats/courses/" + courseId)
                .header("X-User-Id", instructorId.toString()).header("X-User-Role", "INSTRUCTOR");
        return days == null ? request : request.param("days", days);
    }

    @Test
    void theDefaultIsThirtyDaysWithGapsFilledAndTheFunnelFromTheTotals() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        row(today, 2, 4000, 1, 1);
        row(today.minusDays(3), 1, 2000, 0, 0);
        row(today.minusDays(29), 5, 0, 2, 1);
        row(today.minusDays(30), 100, 100, 100, 100); // outside the 30-day window, but inside the totals
        COURSE_SERVICE.courseOwnedBy(courseId, instructorId);

        mockMvc.perform(asOwner(null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(courseId.toString()))
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.series.length()").value(30))
                .andExpect(jsonPath("$.series[0].day").value(today.minusDays(29).toString()))
                .andExpect(jsonPath("$.series[0].enrollments").value(5))
                .andExpect(jsonPath("$.series[26].day").value(today.minusDays(3).toString()))
                .andExpect(jsonPath("$.series[26].enrollments").value(1))
                .andExpect(jsonPath("$.series[27].enrollments").value(0))
                .andExpect(jsonPath("$.series[29].day").value(today.toString()))
                .andExpect(jsonPath("$.series[29].enrollments").value(2))
                .andExpect(jsonPath("$.totals.enrollments").value(108))
                .andExpect(jsonPath("$.funnel.enrolled").value(108))
                .andExpect(jsonPath("$.funnel.completed").value(103))
                .andExpect(jsonPath("$.funnel.certificateIssued").value(102))
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 30, 90})
    void eachAllowedRangeReturnsExactlyThatManyDays(int days) throws Exception {
        COURSE_SERVICE.courseOwnedBy(courseId, instructorId);

        mockMvc.perform(asOwner(String.valueOf(days)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(days))
                .andExpect(jsonPath("$.series.length()").value(days));
    }

    @Test
    void aCourseWithoutActivityReturnsZerosNotAnError() throws Exception {
        COURSE_SERVICE.courseOwnedBy(courseId, instructorId);

        mockMvc.perform(asOwner("7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.enrollments").value(0))
                .andExpect(jsonPath("$.totals.revenueCents").value(0))
                .andExpect(jsonPath("$.series[0].enrollments").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"45", "0", "-7", "abc", "91"})
    void anyOtherRangeIsRejectedWithAFieldErrorOnDays(String days) throws Exception {
        COURSE_SERVICE.courseOwnedBy(courseId, instructorId);

        mockMvc.perform(asOwner(days))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.days").exists())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aCourseIdThatIsNotAUuidIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/instructor/stats/courses/not-a-uuid")
                        .header("X-User-Id", instructorId.toString()).header("X-User-Role", "INSTRUCTOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.courseId").exists());
    }
}
