package com.example.learnhub.certificate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Proof a student completed a course (data-model.md "Certificate", FR-010 to FR-018). At most one per
 * student per course (FR-010), never updated or deleted once issued — no revocation in this feature
 * (spec.md Assumptions).
 */
@Entity
@Table(name = "certificates")
public class Certificate {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(name = "verification_code", nullable = false, updatable = false)
    private String verificationCode;

    @Column(name = "course_title", nullable = false, updatable = false)
    private String courseTitle;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    protected Certificate() {
        // JPA
    }

    public Certificate(UUID id, UUID userId, UUID courseId, String verificationCode, String courseTitle,
            Instant issuedAt) {
        this.id = id;
        this.userId = userId;
        this.courseId = courseId;
        this.verificationCode = verificationCode;
        this.courseTitle = courseTitle;
        this.issuedAt = issuedAt;
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

    public String getVerificationCode() {
        return verificationCode;
    }

    public String getCourseTitle() {
        return courseTitle;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }
}
