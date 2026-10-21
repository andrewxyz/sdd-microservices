package com.example.learnhub.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.review.infrastructure.ReviewRepository;
import com.example.learnhub.review.support.AbstractReviewServiceIT;
import com.example.learnhub.review.support.EnrollmentServiceStub;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test for User Story 1 (FR-001, FR-002, FR-003; research.md R2): an enrolled student can
 * submit a review, a repeat submission upserts rather than duplicates, a non-enrolled student is rejected,
 * and an enrollment-lookup failure rejects the write rather than silently approving it.
 */
class ReviewSubmissionIT extends AbstractReviewServiceIT {

    private static final EnrollmentServiceStub ENROLLMENT_SERVICE = new EnrollmentServiceStub();

    @BeforeAll
    static void startStub() {
        ENROLLMENT_SERVICE.start();
    }

    @AfterAll
    static void stopStub() {
        ENROLLMENT_SERVICE.stop();
    }

    @DynamicPropertySource
    static void enrollmentServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.enrollment-service.url", ENROLLMENT_SERVICE::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReviewRepository reviews;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanUp() {
        reviews.deleteAll();
    }

    @Test
    void anEnrolledStudentCanSubmitAReview() throws Exception {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.enrolled(courseId, userId);

        mockMvc.perform(post("/api/v1/reviews/courses/{courseId}", courseId)
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteBody(5, "Clear and practical."))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rating", is(5)))
                .andExpect(jsonPath("$.comment", is("Clear and practical.")));

        assertThat(reviews.findByUserIdAndCourseId(userId, courseId)).isPresent();
    }

    @Test
    void aRepeatSubmissionUpdatesTheSameRowInsteadOfCreatingADuplicate() throws Exception {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.enrolled(courseId, userId);

        String firstResponse = mockMvc
                .perform(post("/api/v1/reviews/courses/{courseId}", courseId)
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteBody(3, "Okay."))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reviewId = objectMapper.readTree(firstResponse).get("id").asText();

        mockMvc.perform(post("/api/v1/reviews/courses/{courseId}", courseId)
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteBody(5, "Actually great."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(reviewId)))
                .andExpect(jsonPath("$.rating", is(5)))
                .andExpect(jsonPath("$.comment", is("Actually great.")));

        assertThat(reviews.findAll()).hasSize(1);
    }

    @Test
    void aNonEnrolledStudentIsRejected() throws Exception {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.notEnrolled(courseId, userId);

        mockMvc.perform(post("/api/v1/reviews/courses/{courseId}", courseId)
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteBody(4, "Nice"))))
                .andExpect(status().isForbidden());

        assertThat(reviews.findByUserIdAndCourseId(userId, courseId)).isEmpty();
    }

    @Test
    void anEnrollmentLookupFailureRejectsTheWrite() throws Exception {
        UUID courseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ENROLLMENT_SERVICE.unavailable(courseId, userId);

        mockMvc.perform(post("/api/v1/reviews/courses/{courseId}", courseId)
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteBody(4, "Nice"))))
                .andExpect(status().isServiceUnavailable());

        assertThat(reviews.findByUserIdAndCourseId(userId, courseId)).isEmpty();
    }

    private record WriteBody(int rating, String comment) {
    }
}
