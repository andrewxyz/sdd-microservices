package com.example.learnhub.review;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.review.domain.Review;
import com.example.learnhub.review.domain.ReviewStatus;
import com.example.learnhub.review.infrastructure.ReviewRepository;
import com.example.learnhub.review.support.AbstractReviewServiceIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test for User Story 3 (FR-008, FR-009; SC-003): anyone, with no {@code Authorization}
 * header, can list a course's active reviews and see its aggregate rating.
 */
class ReviewBrowsingIT extends AbstractReviewServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReviewRepository reviews;

    @AfterEach
    void cleanUp() {
        reviews.deleteAll();
    }

    @Test
    void listingReturnsOnlyActiveReviews() throws Exception {
        UUID courseId = UUID.randomUUID();
        Review active = newReview(courseId, 5, ReviewStatus.ACTIVE);
        Review deleted = newReview(courseId, 1, ReviewStatus.DELETED);
        reviews.save(active);
        reviews.save(deleted);

        mockMvc.perform(get("/api/v1/reviews/courses/{courseId}", courseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(1)))
                .andExpect(jsonPath("$.items[0].id", is(active.getId().toString())))
                .andExpect(jsonPath("$.totalItems", is(1)));
    }

    @Test
    void summaryMatchesTheAverageAndCountOfActiveReviews() throws Exception {
        UUID courseId = UUID.randomUUID();
        reviews.save(newReview(courseId, 4, ReviewStatus.ACTIVE));
        reviews.save(newReview(courseId, 2, ReviewStatus.ACTIVE));
        reviews.save(newReview(courseId, 5, ReviewStatus.DELETED));

        mockMvc.perform(get("/api/v1/reviews/courses/{courseId}/summary", courseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount", is(2)))
                .andExpect(jsonPath("$.averageRating", is(3.0)));
    }

    @Test
    void aCourseWithZeroActiveReviewsHasNoReviewsYet() throws Exception {
        UUID courseId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/reviews/courses/{courseId}/summary", courseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewCount", is(0)))
                .andExpect(jsonPath("$.averageRating").doesNotExist());
    }

    @Test
    void listingAndSummaryRequireNoAuthorizationHeader() throws Exception {
        UUID courseId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/reviews/courses/{courseId}", courseId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reviews/courses/{courseId}/summary", courseId)).andExpect(status().isOk());
    }

    private Review newReview(UUID courseId, int rating, ReviewStatus status) {
        Instant now = Instant.now();
        return new Review(UUID.randomUUID(), UUID.randomUUID(), courseId, (short) rating, "comment", status, now,
                now);
    }
}
