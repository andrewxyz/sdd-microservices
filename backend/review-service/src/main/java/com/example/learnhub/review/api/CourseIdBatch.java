package com.example.learnhub.review.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Parses the {@code courseIds} query of the batch summaries endpoint: 1 to 50 distinct, comma-separated UUIDs
 * in request order (FR-001, FR-002; data-model.md batch request rules). Anything else is a 400.
 */
public final class CourseIdBatch {

    public static final int MAX_IDS = 50;

    private CourseIdBatch() {
    }

    public static List<UUID> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "courseIds must contain at least one id.");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            try {
                ids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "courseIds contains a malformed id.");
            }
        }
        if (ids.size() > MAX_IDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "courseIds must contain at most " + MAX_IDS + " ids.");
        }
        return new ArrayList<>(ids);
    }
}
