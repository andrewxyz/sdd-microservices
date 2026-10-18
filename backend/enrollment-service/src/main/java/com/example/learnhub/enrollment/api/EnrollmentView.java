package com.example.learnhub.enrollment.api;

import com.example.learnhub.enrollment.domain.Enrollment;
import java.time.Instant;
import java.util.UUID;

/** The client-facing shape of an {@link Enrollment} (contracts/enrollment-service.openapi.yaml). */
public record EnrollmentView(UUID id, UUID courseId, Instant enrolledAt) {

    public static EnrollmentView of(Enrollment enrollment) {
        return new EnrollmentView(enrollment.getId(), enrollment.getCourseId(), enrollment.getEnrolledAt());
    }
}
