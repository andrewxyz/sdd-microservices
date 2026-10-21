package com.example.learnhub.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.common.identity.IdentityHeaders;
import com.example.learnhub.review.domain.Review;
import com.example.learnhub.review.domain.ReviewStatus;
import com.example.learnhub.review.infrastructure.ReviewRepository;
import com.example.learnhub.review.support.AbstractReviewServiceIT;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test for User Story 2 (FR-004, FR-005, FR-006): a student can edit or soft-delete their own
 * review; a non-owner's attempt is rejected identically to a not-found review.
 */
class ReviewOwnershipIT extends AbstractReviewServiceIT {

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
    void theOwnerCanEditTheirReview() throws Exception {
        UUID userId = UUID.randomUUID();
        Review review = persistReview(userId, UUID.randomUUID(), 3, ReviewStatus.ACTIVE);

        mockMvc.perform(put("/api/v1/reviews/{reviewId}", review.getId())
                        .header(IdentityHeaders.USER_ID, userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteBody(5, "Updated."))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating", is(5)))
                .andExpect(jsonPath("$.comment", is("Updated.")));
    }

    @Test
    void theOwnerCanDeleteTheirReviewAndItDisappearsFromListingAndSummary() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        Review review = persistReview(userId, courseId, 4, ReviewStatus.ACTIVE);

        mockMvc.perform(delete("/api/v1/reviews/{reviewId}", review.getId())
                        .header(IdentityHeaders.USER_ID, userId.toString()))
                .andExpect(status().isNoContent());

        assertThat(reviews.findById(review.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.DELETED);

        mockMvc.perform(get("/api/v1/reviews/courses/{courseId}", courseId))
                .andExpect(jsonPath("$.items.length()", is(0)));
        mockMvc.perform(get("/api/v1/reviews/courses/{courseId}/summary", courseId))
                .andExpect(jsonPath("$.reviewCount", is(0)));
    }

    @Test
    void aDifferentStudentsEditAttemptReturnsNotFoundAndLeavesTheReviewUnchanged() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Review review = persistReview(ownerId, UUID.randomUUID(), 3, ReviewStatus.ACTIVE);

        mockMvc.perform(put("/api/v1/reviews/{reviewId}", review.getId())
                        .header(IdentityHeaders.USER_ID, otherId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteBody(1, "Hijacked."))))
                .andExpect(status().isNotFound());

        assertThat(reviews.findById(review.getId()).orElseThrow().getRating()).isEqualTo((short) 3);
    }

    @Test
    void aDifferentStudentsDeleteAttemptReturnsNotFoundAndLeavesTheReviewUnchanged() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Review review = persistReview(ownerId, UUID.randomUUID(), 3, ReviewStatus.ACTIVE);

        mockMvc.perform(delete("/api/v1/reviews/{reviewId}", review.getId())
                        .header(IdentityHeaders.USER_ID, otherId.toString()))
                .andExpect(status().isNotFound());

        assertThat(reviews.findById(review.getId()).orElseThrow().getStatus()).isEqualTo(ReviewStatus.ACTIVE);
    }

    @Test
    void editingANonExistentReviewReturnsNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/reviews/{reviewId}", UUID.randomUUID())
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new WriteBody(2, "No one home."))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingANonExistentReviewReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/api/v1/reviews/{reviewId}", UUID.randomUUID())
                        .header(IdentityHeaders.USER_ID, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    private Review persistReview(UUID userId, UUID courseId, int rating, ReviewStatus status) {
        Instant now = Instant.now();
        Review review = new Review(UUID.randomUUID(), userId, courseId, (short) rating, "original", status, now,
                now);
        return reviews.save(review);
    }

    private record WriteBody(int rating, String comment) {
    }
}
