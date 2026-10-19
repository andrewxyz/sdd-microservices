package com.example.learnhub.notification.domain;

/**
 * research.md R7's two-phase write: {@code PENDING} means the row was written but the send has not yet
 * been confirmed (a prior attempt may have crashed between phases); {@code SENT} means the email was
 * handed to Mailpit successfully and a redelivery of this event must never send it again (FR-022).
 */
public enum NotificationStatus {
    PENDING,
    SENT
}
