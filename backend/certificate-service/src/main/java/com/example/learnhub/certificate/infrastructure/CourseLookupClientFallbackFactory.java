package com.example.learnhub.certificate.infrastructure;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * research.md R5: this fallback always throws — there is no degraded result to return. A thrown
 * exception from {@code CourseCompletedListener} (which runs inside a {@code @RabbitListener} method)
 * propagates out of the listener, causing the message to retry rather than issuing a titleless
 * certificate (the brief's explicit "failing the message so it retries, if unreachable").
 */
@Component
public class CourseLookupClientFallbackFactory implements FallbackFactory<CourseLookupClient> {

    @Override
    public CourseLookupClient create(Throwable cause) {
        return courseId -> {
            throw new CourseLookupUnavailableException(courseId, cause);
        };
    }
}
