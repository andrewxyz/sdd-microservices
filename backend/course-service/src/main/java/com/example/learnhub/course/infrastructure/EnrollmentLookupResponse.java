package com.example.learnhub.course.infrastructure;

import java.time.Instant;

/**
 * Mirrors enrollment-service's {@code EnrollmentLookupResponse} (specs/004-checkout-enrollment), the
 * shape returned by {@code GET /api/v1/internal/enrollments/{courseId}/{userId}}. A plain copy, not a
 * shared type (constitution Principle II).
 */
public record EnrollmentLookupResponse(boolean enrolled, Instant enrolledAt) {
}
