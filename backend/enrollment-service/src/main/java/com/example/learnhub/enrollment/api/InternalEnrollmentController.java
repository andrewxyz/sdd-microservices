package com.example.learnhub.enrollment.api;

import com.example.learnhub.enrollment.domain.Enrollment;
import com.example.learnhub.enrollment.infrastructure.EnrollmentRepository;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/internal/enrollments/{courseId}/{userId}} — research.md R4, R5. No gateway route
 * targets this path; reachable only by course-service calling {@code lb://enrollment-service} directly.
 */
@RestController
@RequestMapping("/api/v1/internal/enrollments")
public class InternalEnrollmentController {

    private final EnrollmentRepository enrollments;

    public InternalEnrollmentController(EnrollmentRepository enrollments) {
        this.enrollments = enrollments;
    }

    @GetMapping("/{courseId}/{userId}")
    public EnrollmentLookupResponse lookup(@PathVariable UUID courseId, @PathVariable UUID userId) {
        return enrollments.findByUserIdAndCourseId(userId, courseId)
                .map(InternalEnrollmentController::found)
                .orElseGet(EnrollmentLookupResponse::notEnrolled);
    }

    private static EnrollmentLookupResponse found(Enrollment enrollment) {
        return new EnrollmentLookupResponse(true, enrollment.getEnrolledAt());
    }
}
