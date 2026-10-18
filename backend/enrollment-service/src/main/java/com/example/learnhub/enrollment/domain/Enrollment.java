package com.example.learnhub.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Evidence that a user has access to a course (data-model.md "Enrollment"). Created only as the
 * result of a consumed {@code payment.succeeded} event (research.md R3); no update or delete operation
 * exists in this feature (unenrolling is out of scope, spec.md).
 */
@Entity
@Table(name = "enrollments")
public class Enrollment {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    @Column(name = "source_payment_id", nullable = false, updatable = false)
    private UUID sourcePaymentId;

    protected Enrollment() {
        // JPA
    }

    public Enrollment(UUID id, UUID userId, UUID courseId, Instant enrolledAt, UUID sourcePaymentId) {
        this.id = id;
        this.userId = userId;
        this.courseId = courseId;
        this.enrolledAt = enrolledAt;
        this.sourcePaymentId = sourcePaymentId;
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

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public UUID getSourcePaymentId() {
        return sourcePaymentId;
    }
}
