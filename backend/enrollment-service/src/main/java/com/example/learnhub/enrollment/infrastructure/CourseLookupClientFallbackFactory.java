package com.example.learnhub.enrollment.infrastructure;

import java.util.UUID;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * research.md R5: enrollment-service calls this only for a course its caller already has an enrollment
 * in, so there is no legitimate "not found" case to distinguish the way payment-service's own
 * {@code CourseLookupClientFallbackFactory} does (004) — every failure here, 404 included, is
 * unexpected and fails closed with a 503 directly to the caller (the brief's explicit instruction).
 */
@Component
public class CourseLookupClientFallbackFactory implements FallbackFactory<CourseLookupClient> {

    @Override
    public CourseLookupClient create(Throwable cause) {
        return courseId -> {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not confirm the course's lesson details right now. Try again shortly.", cause);
        };
    }
}
