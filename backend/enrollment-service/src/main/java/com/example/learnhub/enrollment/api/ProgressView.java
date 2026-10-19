package com.example.learnhub.enrollment.api;

import java.time.Instant;
import java.util.UUID;

/** {@code GET .../progress} response (contracts/enrollment-service-progress.openapi.yaml, FR-007). */
public record ProgressView(UUID courseId, long completedLessons, long totalLessons,
        double percentComplete, Instant courseCompletedAt) {
}
