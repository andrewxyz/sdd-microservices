package com.example.learnhub.review.infrastructure;

import java.time.Instant;

/**
 * Plain copy of enrollment-service's own response shape (constitution Principle II — no shared domain
 * type), mirroring course-service's identical copy (research.md R2).
 */
public record EnrollmentLookupResponse(boolean enrolled, Instant enrolledAt) {
}
