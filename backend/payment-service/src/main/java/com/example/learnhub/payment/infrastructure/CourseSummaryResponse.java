package com.example.learnhub.payment.infrastructure;

import java.util.UUID;

/**
 * Mirrors course-service's {@code CourseSummaryView} (specs/003-course-catalog), the shape returned by
 * {@code GET /api/v1/internal/courses/{courseId}}. A plain copy, not a shared type (constitution
 * Principle II: no domain logic in a shared library) — the two services are coupled only by this JSON
 * contract.
 */
public record CourseSummaryResponse(UUID courseId, String status, Integer priceCents, String currency,
        UUID instructorId, long lessonCount) {
}
