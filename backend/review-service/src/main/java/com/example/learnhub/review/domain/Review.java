package com.example.learnhub.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An enrolled student's rating and optional comment for a course (data-model.md "Review", FR-001 to
 * FR-012). At most one row per (user, course) pair, enforced by a unique constraint in the Flyway
 * migration; a later submission for the same pair updates this row in place rather than creating a new
 * one (FR-003), including reactivating a {@code DELETED} row back to {@code ACTIVE} (data-model.md's
 * state-transition note).
 */
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(name = "rating", nullable = false)
    private short rating;

    @Column(name = "comment", length = 500)
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReviewStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Review() {
        // JPA
    }

    public Review(UUID id, UUID userId, UUID courseId, short rating, String comment, ReviewStatus status,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.userId = userId;
        this.courseId = courseId;
        this.rating = rating;
        this.comment = comment;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public short getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Applies a new rating/comment and reactivates the row to {@code ACTIVE} (FR-003, FR-004). */
    public void applyEdit(short rating, String comment, Instant updatedAt) {
        this.rating = rating;
        this.comment = comment;
        this.status = ReviewStatus.ACTIVE;
        this.updatedAt = updatedAt;
    }

    /** Soft-deletes this review (FR-005). */
    public void markDeleted(Instant updatedAt) {
        this.status = ReviewStatus.DELETED;
        this.updatedAt = updatedAt;
    }
}
