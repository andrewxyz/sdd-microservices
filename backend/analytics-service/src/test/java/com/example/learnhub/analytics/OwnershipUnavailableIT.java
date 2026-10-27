package com.example.learnhub.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.analytics.support.AbstractAnalyticsServiceIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * US5 (spec FR-016, SC-006): when course ownership cannot be confirmed the answer is a 503 with a
 * reference code and no figures at all, including when the circuit breaker has opened, and it recovers
 * on its own. One sequential test, because the circuit breaker's state is the thing under test.
 */
class OwnershipUnavailableIT extends AbstractAnalyticsServiceIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    private final UUID instructorId = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();

    private MockHttpServletRequestBuilder instructor(String path) {
        return get(path).header("X-User-Id", instructorId.toString()).header("X-User-Role", "INSTRUCTOR");
    }

    private MockHttpServletRequestBuilder administrator(String path) {
        return get(path).header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "ADMINISTRATOR");
    }

    private void expectNoFigures(String path) throws Exception {
        String body = mockMvc.perform(instructor(path))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn().getResponse().getContentAsString();
        JsonNode problem = JSON.readTree(body);
        assertThat(problem.has("totals")).as("no totals in %s", body).isFalse();
        assertThat(problem.has("items")).as("no items in %s", body).isFalse();
        assertThat(problem.has("series")).as("no series in %s", body).isFalse();
        assertThat(problem.has("funnel")).as("no funnel in %s", body).isFalse();
    }

    @Test
    void failsClosedWhileCourseServiceIsDownAndAfterTheCircuitOpensThenRecovers() throws Exception {
        jdbc.update("INSERT INTO course_daily_stats VALUES (?, ?, 3, 3000, 1, 1)", courseId,
                Date.valueOf(LocalDate.now(ZoneOffset.UTC)));

        // Phase A: course-service answers 500 to everything. Enough calls to open the circuit breaker.
        COURSE_SERVICE.failEverything();
        for (int i = 0; i < 6; i++) {
            expectNoFigures("/api/v1/instructor/stats/summary");
        }
        expectNoFigures("/api/v1/instructor/stats/courses");
        expectNoFigures("/api/v1/instructor/stats/courses/" + courseId);

        // Phase B: course-service is healthy again, but the circuit is open, so the call is refused without
        // being made. Still no figures.
        COURSE_SERVICE.reset();
        COURSE_SERVICE.owns(instructorId, List.of(courseId));
        COURSE_SERVICE.courseOwnedBy(courseId, instructorId);
        int callsBefore = COURSE_SERVICE.requestCount();
        expectNoFigures("/api/v1/instructor/stats/summary");
        assertThat(COURSE_SERVICE.requestCount()).as("an open circuit makes no call").isEqualTo(callsBefore);

        // Phase C: an administrator's platform views need no ownership lookup, so they work even while the list
        // lookup's circuit is open. (A single course has its own circuit, which phase A did not trip; its failure
        // path is covered by phase A, where the instructor's course request got a 503.)
        mockMvc.perform(administrator("/api/v1/instructor/stats/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.enrollments").value(3));
        mockMvc.perform(administrator("/api/v1/instructor/stats/courses")).andExpect(status().isOk());

        // Phase D: after the open state elapses the breaker lets calls through again and the figures return.
        boolean recovered = eventually(() -> {
            try {
                return mockMvc.perform(instructor("/api/v1/instructor/stats/summary")).andReturn().getResponse()
                        .getStatus() == 200;
            } catch (Exception e) {
                return false;
            }
        }, 30_000);
        assertThat(recovered).as("the dashboard recovers by itself").isTrue();
        mockMvc.perform(instructor("/api/v1/instructor/stats/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.enrollments").value(3))
                .andExpect(jsonPath("$.totals.revenueCents").value(3000));
    }
}
