package com.example.learnhub.enrollment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import com.example.learnhub.enrollment.support.AbstractEnrollmentServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** FR-018: a user lists only their own enrollments, never anyone else's (US1 scenario 6). */
class MyEnrollmentsIT extends AbstractEnrollmentServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EnrollmentRepository enrollments;

    @Test
    void listsOnlyTheCallersOwnEnrollments() throws Exception {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID courseForA = UUID.randomUUID();
        UUID courseForB = UUID.randomUUID();

        enrollments.save(new Enrollment(UUID.randomUUID(), userA, courseForA, Instant.now(), UUID.randomUUID()));
        enrollments.save(new Enrollment(UUID.randomUUID(), userB, courseForB, Instant.now(), UUID.randomUUID()));

        mockMvc.perform(get("/api/v1/enrollments").header(IdentityHeaders.USER_ID, userA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].courseId").value(courseForA.toString()));

        mockMvc.perform(get("/api/v1/enrollments").header(IdentityHeaders.USER_ID, userB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].courseId").value(courseForB.toString()));
    }
}
