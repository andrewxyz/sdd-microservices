package com.example.learnhub.payment.infrastructure;

import feign.FeignException;
import java.util.UUID;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * research.md R4: a confirmed 404 from course-service becomes a clean "no such course" (so checkout can
 * still answer 404, not 503); anything else — a timeout, a 5xx, or the circuit itself being open —
 * becomes a 503, since checkout cannot safely proceed without confirming the course's price and status.
 */
@Component
public class CourseLookupClientFallbackFactory implements FallbackFactory<CourseLookupClient> {

    @Override
    public CourseLookupClient create(Throwable cause) {
        return courseId -> fallback(courseId, cause);
    }

    private CourseSummaryResponse fallback(UUID courseId, Throwable cause) {
        if (cause instanceof FeignException.NotFound) {
            return null;
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not confirm the course's details right now. Try again shortly.", cause);
    }
}
