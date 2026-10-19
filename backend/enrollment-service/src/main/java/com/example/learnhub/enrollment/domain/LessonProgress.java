package com.example.learnhub.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

/**
 * Whether a specific enrollment has completed a specific lesson (data-model.md "LessonProgress",
 * FR-001 to FR-006). Absence of a row *is* "not complete" — there is no boolean flag to flip back to
 * false; unmarking deletes the row (FR-005).
 */
@Entity
@Table(name = "lesson_progress")
public class LessonProgress {

    @Id
    private UUID id;

    @Column(name = "enrollment_id", nullable = false, updatable = false)
    private UUID enrollmentId;

    @Column(name = "lesson_id", nullable = false, updatable = false)
    private UUID lessonId;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    protected LessonProgress() {
        // JPA
    }

    public LessonProgress(UUID id, UUID enrollmentId, UUID lessonId, Instant completedAt) {
        this.id = id;
        this.enrollmentId = enrollmentId;
        this.lessonId = lessonId;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEnrollmentId() {
        return enrollmentId;
    }

    public UUID getLessonId() {
        return lessonId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
