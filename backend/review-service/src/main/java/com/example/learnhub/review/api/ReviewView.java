package com.example.learnhub.review.api;

import com.example.learnhub.review.domain.Review;
import java.time.Instant;
import java.util.UUID;

/** Public shape of a review (contracts/review-service.openapi.yaml's {@code ReviewView}). */
public record ReviewView(UUID id, UUID courseId, UUID userId, int rating, String comment, Instant createdAt,
        Instant updatedAt) {

    public static ReviewView of(Review review) {
        return new ReviewView(review.getId(), review.getCourseId(), review.getUserId(), review.getRating(),
                review.getComment(), review.getCreatedAt(), review.getUpdatedAt());
    }
}
