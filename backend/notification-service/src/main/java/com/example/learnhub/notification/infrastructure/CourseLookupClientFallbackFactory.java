package com.example.learnhub.notification.infrastructure;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/** research.md R5: throws on every failure, so the message retries rather than sending a broken email. */
@Component
public class CourseLookupClientFallbackFactory implements FallbackFactory<CourseLookupClient> {

    @Override
    public CourseLookupClient create(Throwable cause) {
        return courseId -> {
            throw new LookupUnavailableException("course", courseId, cause);
        };
    }
}
