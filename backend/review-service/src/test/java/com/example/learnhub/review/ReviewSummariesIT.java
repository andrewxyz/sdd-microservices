package com.example.learnhub.review;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.learnhub.review.domain.Review;
import com.example.learnhub.review.domain.ReviewStatus;
import com.example.learnhub.review.infrastructure.ReviewRepository;
import com.example.learnhub.review.support.AbstractReviewServiceIT;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test for User Story 1 (FR-001, FR-002; SC-001): the public batch endpoint returns one rating
 * summary per requested course id, with no {@code Authorization} header.
 */
class ReviewSummariesIT extends AbstractReviewServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReviewRepository reviews;

    @AfterEach
    void cleanUp() {
        reviews.deleteAll();
    }

    @Test
    void returnsAverageAndCountForAReviewedCourseAndZeroForAnUnreviewedOne() throws Exception {
        UUID reviewed = UUID.randomUUID();
        UUID unreviewed = UUID.randomUUID();
        reviews.save(newReview(reviewed, 4, ReviewStatus.ACTIVE));
        reviews.save(newReview(reviewed, 2, ReviewStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/reviews/summaries").param("courseIds", reviewed + "," + unreviewed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()", is(2)))
                .andExpect(jsonPath("$.items[0].courseId", is(reviewed.toString())))
                .andExpect(jsonPath("$.items[0].reviewCount", is(2)))
                .andExpect(jsonPath("$.items[0].averageRating", is(3.0)))
                .andExpect(jsonPath("$.items[1].courseId", is(unreviewed.toString())))
                .andExpect(jsonPath("$.items[1].reviewCount", is(0)))
                .andExpect(jsonPath("$.items[1].averageRating", nullValue()));
    }

    @Test
    void excludesDeletedReviews() throws Exception {
        UUID courseId = UUID.randomUUID();
        reviews.save(newReview(courseId, 5, ReviewStatus.ACTIVE));
        reviews.save(newReview(courseId, 1, ReviewStatus.DELETED));

        mockMvc.perform(get("/api/v1/reviews/summaries").param("courseIds", courseId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reviewCount", is(1)))
                .andExpect(jsonPath("$.items[0].averageRating", is(5.0)));
    }

    @Test
    void keepsRequestOrder() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        reviews.save(newReview(second, 3, ReviewStatus.ACTIVE));

        mockMvc.perform(get("/api/v1/reviews/summaries").param("courseIds", first + "," + second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].courseId", is(first.toString())))
                .andExpect(jsonPath("$.items[1].courseId", is(second.toString())));
    }

    @Test
    void fiftyOneIdsReturn400() throws Exception {
        String ids = IntStream.range(0, 51).mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));

        mockMvc.perform(get("/api/v1/reviews/summaries").param("courseIds", ids))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMalformedIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/summaries").param("courseIds", "nope"))
                .andExpect(status().isBadRequest());
    }

    private Review newReview(UUID courseId, int rating, ReviewStatus status) {
        Instant now = Instant.now();
        return new Review(UUID.randomUUID(), UUID.randomUUID(), courseId, (short) rating, "comment", status, now,
                now);
    }
}
