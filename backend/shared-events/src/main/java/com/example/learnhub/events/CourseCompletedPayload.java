package com.example.learnhub.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code EventEnvelope.payload} shape for {@code type = "course.completed"}, version 1
 * (specs/005-progress-certificates-notifications/contracts/course-completed.event.schema.json).
 * Published by enrollment-service; consumed by certificate-service.
 */
public record CourseCompletedPayload(UUID enrollmentId, UUID userId, UUID courseId, Instant completedAt) {
}
