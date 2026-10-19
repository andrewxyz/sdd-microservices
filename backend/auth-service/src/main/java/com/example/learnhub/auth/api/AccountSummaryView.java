package com.example.learnhub.auth.api;

import com.example.learnhub.auth.domain.Account;
import java.util.UUID;

/**
 * The service-to-service account lookup response (specs/005-progress-certificates-notifications
 * research.md R4): {@code id} and {@code email} only — no password hash, role, or timestamp.
 */
public record AccountSummaryView(UUID id, String email) {

    public static AccountSummaryView of(Account account) {
        return new AccountSummaryView(account.getId(), account.getEmail());
    }
}
