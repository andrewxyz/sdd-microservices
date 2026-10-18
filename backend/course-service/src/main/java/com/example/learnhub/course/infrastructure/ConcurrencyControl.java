package com.example.learnhub.course.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Optimistic-concurrency guard (FR-025, research.md R1): every mutating request to a `Course`,
 * `Lesson` or `Category` must carry the resource's current {@code version} as {@code If-Match}, or be
 * refused with 409, rather than silently applying a save over an intervening change.
 */
public final class ConcurrencyControl {

    private ConcurrencyControl() {
    }

    public static void requireMatch(String ifMatchHeader, long currentVersion) {
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "If-Match header is required.");
        }
        long presented;
        try {
            presented = Long.parseLong(unquote(ifMatchHeader));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "If-Match header is not a valid version.");
        }
        if (presented != currentVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This resource changed since it was last read. Reload and try again.");
        }
    }

    public static String etagOf(long version) {
        return "\"" + version + "\"";
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
