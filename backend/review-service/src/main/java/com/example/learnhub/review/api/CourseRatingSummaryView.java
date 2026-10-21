package com.example.learnhub.review.api;

import java.util.UUID;

/**
 * A course's aggregate rating (FR-009; contracts/review-service.openapi.yaml's {@code CourseRatingSummary}).
 * {@code averageRating} is {@code null} when {@code reviewCount} is 0 (no reviews yet, Edge Cases).
 */
public record CourseRatingSummaryView(UUID courseId, Double averageRating, long reviewCount) {
}
