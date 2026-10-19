package com.example.learnhub.notification.infrastructure;

import java.util.UUID;

/** Mirrors auth-service's {@code AccountSummaryView} (research.md R4): {@code id} and {@code email} only. */
public record AccountSummaryResponse(UUID id, String email) {
}
