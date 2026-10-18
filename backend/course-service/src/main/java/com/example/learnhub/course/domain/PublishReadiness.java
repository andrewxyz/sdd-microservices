package com.example.learnhub.course.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * FR-014's completeness check: title, description, category, price (zero counts, FR-015), and at
 * least one lesson. Used both when publishing or republishing (FR-014, FR-017) and when editing an
 * already-published course (FR-018) — a published course must never be left in a state this check
 * would fail, so edits are validated against it too.
 */
public final class PublishReadiness {

    private PublishReadiness() {
    }

    /** Empty when the course is publish-ready; otherwise the specific parts that are missing. */
    public static List<String> violations(Course course) {
        List<String> violations = new ArrayList<>();
        if (course.getTitle() == null || course.getTitle().isBlank()) {
            violations.add("title is required");
        }
        if (course.getDescription() == null || course.getDescription().isBlank()) {
            violations.add("description is required");
        }
        if (course.getCategoryId() == null) {
            violations.add("category is required");
        }
        if (course.getPriceCents() == null) {
            violations.add("price is required");
        }
        if (course.getLessons().isEmpty()) {
            violations.add("at least one lesson is required");
        }
        return violations;
    }
}
