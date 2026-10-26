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
    void listingIsNewestFirstAndPagingNeverRepeatsOrSkipsAReview() throws Exception {
        UUID courseId = UUID.randomUUID();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        // Saved oldest first, so the database's natural order is the opposite of what the API must return.
        Review oldest = reviewAt(courseId, base.plusSeconds(1));
        Review middle = reviewAt(courseId, base.plusSeconds(2));
        Review newest = reviewAt(courseId, base.plusSeconds(3));
        Review sameTimeAsNewest = reviewAt(courseId, base.plusSeconds(3));
        reviews.save(oldest);
        reviews.save(middle);
        reviews.save(newest);
        reviews.save(sameTimeAsNewest);

        String first = mockMvc.perform(get("/api/v1/reviews/courses/{courseId}?page=0&size=2", courseId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/v1/reviews/courses/{courseId}?page=1&size=2", courseId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String body : new String[] {first, second}) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(body);
            while (m.find()) {
                ids.add(m.group(1));
            }
        }
        org.assertj.core.api.Assertions.assertThat(ids).hasSize(4).doesNotHaveDuplicates();
        // The two newest (equal timestamps) come first in either order; then middle, then oldest.
        org.assertj.core.api.Assertions.assertThat(ids.subList(0, 2))
                .containsExactlyInAnyOrder(newest.getId().toString(), sameTimeAsNewest.getId().toString());
        org.assertj.core.api.Assertions.assertThat(ids.get(2)).isEqualTo(middle.getId().toString());
        org.assertj.core.api.Assertions.assertThat(ids.get(3)).isEqualTo(oldest.getId().toString());
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

    private Review reviewAt(UUID courseId, Instant createdAt) {
        return new Review(UUID.randomUUID(), UUID.randomUUID(), courseId, (short) 3, "comment", ReviewStatus.ACTIVE,
                createdAt, createdAt);
    }

    private Review newReview(UUID courseId, int rating, ReviewStatus status) {
        Instant now = Instant.now();
        return new Review(UUID.randomUUID(), UUID.randomUUID(), courseId, (short) rating, "comment", status, now,
                now);
    }
}
