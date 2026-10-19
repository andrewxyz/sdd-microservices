package com.example.learnhub.certificate.infrastructure;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors course-service's {@code CourseSummaryView} (specs/003-course-catalog, extended in
 * specs/005-progress-certificates-notifications research.md R3). Only {@code title} is actually used by
 * certificate-service; the rest is declared so Jackson can deserialize the full response without
 * complaint.
 */
public record CourseSummaryResponse(UUID courseId, String status, Integer priceCents, String currency,
        UUID instructorId, long lessonCount, String title, List<UUID> lessonIds) {
}
