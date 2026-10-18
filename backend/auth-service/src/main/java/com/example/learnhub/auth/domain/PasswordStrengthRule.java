package com.example.learnhub.auth.domain;

import java.util.Optional;

/**
 * "Minimum 10 characters, containing at least one letter and one digit" (FR-003). Returns the specific
 * part of the rule that failed, so the caller can state it (FR-003 requires naming the reason, not just
 * refusing).
 */
public final class PasswordStrengthRule {

    private static final int MIN_LENGTH = 10;

    private PasswordStrengthRule() {
    }

    /** Empty when the password is strong enough; otherwise the specific reason it was refused. */
    public static Optional<String> violation(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return Optional.of("Password must be at least " + MIN_LENGTH + " characters long.");
        }
        if (password.chars().noneMatch(Character::isLetter)) {
            return Optional.of("Password must contain at least one letter.");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            return Optional.of("Password must contain at least one digit.");
        }
        return Optional.empty();
    }
}
