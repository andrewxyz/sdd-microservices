package com.example.learnhub.review.infrastructure;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thrown by {@link EnrollmentLookupClientFallback} when enrollment-service cannot be reached to confirm
 * enrollment. A {@link ResponseStatusException} subclass (SERVICE_UNAVAILABLE) so the platform-common
 * {@code ProblemDetailsAdvice} — the single shared error-mapping mechanism used across every business
 * service, confirmed by inspecting payment-service and certificate-service, neither of which defines its
 * own exception hierarchy or controller advice — maps it to a 503 problem-details response without any
 * new, per-service advice (research.md R2: "the write is rejected rather than silently approved").
 */
public class EnrollmentLookupUnavailableException extends ResponseStatusException {

    public EnrollmentLookupUnavailableException(UUID courseId) {
        super(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not confirm your enrollment in course " + courseId + " right now. Try again shortly.");
    }
}
