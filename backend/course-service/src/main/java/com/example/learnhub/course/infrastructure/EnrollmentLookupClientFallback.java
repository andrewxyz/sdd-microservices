package com.example.learnhub.course.infrastructure;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * research.md R4, R5, FR-024: when enrollment status cannot be confirmed — a timeout, a 5xx, or the
 * circuit itself being open — content must be refused with a clear, temporary error, never shown on an
 * unconfirmed basis. There is no legitimate "not found" case for this endpoint (it always answers
 * {@code enrolled: false} rather than 404), so every failure here means exactly one thing: unavailable.
 */
@Component
public class EnrollmentLookupClientFallback implements EnrollmentLookupClient {

    @Override
    public EnrollmentLookupResponse lookup(UUID courseId, UUID userId) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not confirm your enrollment right now. Try again shortly.");
    }
}
