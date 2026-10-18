package com.example.learnhub.enrollment.api;

import java.time.Instant;

/** The service-to-service enrollment check response (research.md R4, R5). */
public record EnrollmentLookupResponse(boolean enrolled, Instant enrolledAt) {

    public static EnrollmentLookupResponse notEnrolled() {
        return new EnrollmentLookupResponse(false, null);
    }
}
