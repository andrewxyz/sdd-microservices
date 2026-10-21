package com.example.learnhub.review.infrastructure;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * research.md R2: when enrollment status cannot be confirmed — a timeout, a 5xx, or the circuit itself
 * being open — the fallback MUST NOT silently treat the student as enrolled (that would let an
 * unenrolled student post a review); it always throws, surfacing a 503 so the write is rejected rather
 * than silently approved or rejected. Mirrors course-service's own {@code EnrollmentLookupClientFallback}.
 */
@Component
public class EnrollmentLookupClientFallback implements EnrollmentLookupClient {

    @Override
    public EnrollmentLookupResponse lookup(UUID courseId, UUID userId) {
        throw new EnrollmentLookupUnavailableException(courseId);
    }
}
