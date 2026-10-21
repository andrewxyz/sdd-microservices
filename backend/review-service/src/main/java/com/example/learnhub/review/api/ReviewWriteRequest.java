package com.example.learnhub.review.api;

import jakarta.validation.constraints.NotNull;

/**
 * Request body shared by {@code POST /reviews/courses/{courseId}} and {@code PUT /reviews/{reviewId}} —
 * identical shape in both of contracts/review-service.openapi.yaml's {@code ReviewWriteRequest} and
 * {@code ReviewEditRequest} schemas, so one type represents both (constitution Principle X).
 *
 * <p>Only {@code rating}'s presence is enforced here (a missing body field is a different failure mode
 * than an out-of-range one). The rating range, comment length and blocked-word checks all live in
 * {@link com.example.learnhub.review.application.ReviewValidator} so there is exactly one place that
 * decides what a valid review looks like (FR-007, FR-012).
 */
public record ReviewWriteRequest(@NotNull Integer rating, String comment) {
}
