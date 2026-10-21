package com.example.learnhub.review.application;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Simple profanity/length validation (FR-007, FR-012; research.md R4): a static blocked-word list plus a
 * maximum comment length of 500 characters, enforced before persisting. No ML moderation, no third-party
 * library (constitution Principle X) — a hardcoded list reviewable in code review is the simplest thing
 * that satisfies the spec.
 */
@Component
public class ReviewValidator {

    static final int MAX_COMMENT_LENGTH = 500;

    private static final Set<String> BLOCKED_WORDS =
            Set.of("idiot", "stupid", "garbage", "crap", "damn", "hell", "scam", "fraud");

    /**
     * @throws ResponseStatusException {@code 400 Bad Request} naming which rule failed.
     */
    public void validate(int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5.");
        }
        if (comment == null) {
            return;
        }
        if (comment.length() > MAX_COMMENT_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Comment must be at most " + MAX_COMMENT_LENGTH + " characters.");
        }
        String normalized = comment.toLowerCase(Locale.ROOT);
        for (String blocked : BLOCKED_WORDS) {
            if (normalized.contains(blocked)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Comment contains language that is not allowed.");
            }
        }
    }
}
