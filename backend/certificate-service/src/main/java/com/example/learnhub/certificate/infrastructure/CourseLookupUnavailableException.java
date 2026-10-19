package com.example.learnhub.certificate.infrastructure;

import java.util.UUID;

/**
 * Thrown by {@link CourseLookupClientFallbackFactory} when course-service cannot be reached to confirm
 * a course's title. Deliberately a plain {@link RuntimeException}, not a {@code ResponseStatusException}
 * — this is thrown inside a {@code @RabbitListener} method, not an HTTP request, so it simply needs to
 * propagate and let the message retry (research.md R5).
 */
public class CourseLookupUnavailableException extends RuntimeException {

    public CourseLookupUnavailableException(UUID courseId, Throwable cause) {
        super("Could not confirm course " + courseId + "'s title right now.", cause);
    }
}
