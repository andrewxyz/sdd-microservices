package com.example.learnhub.enrollment.infrastructure;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors course-service's {@code CourseSummaryView} (specs/003-course-catalog, extended in
 * specs/005-progress-certificates-notifications research.md R3), the shape returned by
 * {@code GET /api/v1/internal/courses/{courseId}}. A plain copy, not a shared type (constitution
 * Principle II) — the two services are coupled only by this JSON contract.
 */
public record CourseSummaryResponse(UUID courseId, String status, Integer priceCents, String currency,
        UUID instructorId, long lessonCount, String title, List<UUID> lessonIds) {
}
