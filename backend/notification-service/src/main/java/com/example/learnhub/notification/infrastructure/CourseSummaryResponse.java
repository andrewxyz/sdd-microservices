package com.example.learnhub.notification.infrastructure;

import java.util.List;
import java.util.UUID;

/**
 * Mirrors course-service's {@code CourseSummaryView} (research.md R3). Only {@code title} is actually
 * used by notification-service.
 */
public record CourseSummaryResponse(UUID courseId, String status, Integer priceCents, String currency,
        UUID instructorId, long lessonCount, String title, List<UUID> lessonIds) {
}
