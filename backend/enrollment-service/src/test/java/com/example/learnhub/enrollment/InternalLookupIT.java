package com.example.learnhub.enrollment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import com.example.learnhub.enrollment.support.AbstractEnrollmentServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** research.md R4, R5: the internal, service-to-service enrollment check consumed by course-service. */
class InternalLookupIT extends AbstractEnrollmentServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EnrollmentRepository enrollments;

    @Test
    void anExistingEnrollmentReportsEnrolledTrueWithATimestamp() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        enrollments.save(new Enrollment(UUID.randomUUID(), userId, courseId, Instant.now(), UUID.randomUUID()));

        mockMvc.perform(get("/api/v1/internal/enrollments/" + courseId + "/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(true))
                .andExpect(jsonPath("$.enrolledAt").isNotEmpty());
    }

    @Test
    void aNonEnrolledUserReportsEnrolledFalseWithNoTimestamp() throws Exception {
        mockMvc.perform(get("/api/v1/internal/enrollments/" + UUID.randomUUID() + "/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.enrolledAt").doesNotExist());
    }
}
