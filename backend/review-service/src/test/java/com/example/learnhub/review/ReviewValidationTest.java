package com.example.learnhub.review;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.learnhub.review.application.ReviewValidator;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit test for {@link ReviewValidator} (FR-007, FR-012; research.md R4). No Spring context needed.
 */
class ReviewValidationTest {

    private final ReviewValidator validator = new ReviewValidator();

    @Test
    void rejectsARatingOfZero() {
        assertThatThrownBy(() -> validator.validate(0, "fine"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void rejectsARatingOfSix() {
        assertThatThrownBy(() -> validator.validate(6, "fine"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void acceptsEveryRatingFromOneToFive() {
        for (int rating = 1; rating <= 5; rating++) {
            int effectivelyFinalRating = rating;
            assertThatCode(() -> validator.validate(effectivelyFinalRating, null)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsA501CharacterComment() {
        String comment = "a".repeat(501);
        assertThatThrownBy(() -> validator.validate(5, comment))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void acceptsA500CharacterComment() {
        String comment = "a".repeat(500);
        assertThatCode(() -> validator.validate(5, comment)).doesNotThrowAnyException();
    }

    @Test
    void rejectsACommentContainingABlockedWord() {
        assertThatThrownBy(() -> validator.validate(3, "This course is garbage."))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void acceptsANullComment() {
        assertThatCode(() -> validator.validate(4, null)).doesNotThrowAnyException();
    }
}
