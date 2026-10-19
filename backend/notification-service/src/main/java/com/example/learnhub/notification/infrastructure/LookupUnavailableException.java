package com.example.learnhub.notification.infrastructure;

import java.util.UUID;

/**
 * Thrown by {@link AccountLookupClientFallbackFactory} or {@link CourseLookupClientFallbackFactory} when
 * the respective service cannot be reached. Deliberately a plain {@link RuntimeException}, not a
 * {@code ResponseStatusException} — this is thrown inside a {@code @RabbitListener} method, not an HTTP
 * request, so it simply needs to propagate and let the message retry (research.md R5).
 */
public class LookupUnavailableException extends RuntimeException {

    public LookupUnavailableException(String subject, UUID id, Throwable cause) {
        super("Could not confirm " + subject + " " + id + " right now.", cause);
    }
}
