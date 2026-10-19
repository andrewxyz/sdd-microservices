package com.example.learnhub.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to turn a consumed event into an email (data-model.md "Notification", research.md R7).
 * No email address or body is ever persisted here (FR-021) — the address is resolved fresh from
 * auth-service each time it's needed and never written to disk by notification-service itself.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
        // JPA
    }

    public Notification(UUID id, UUID eventId, UUID userId, NotificationType type, Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.userId = userId;
        this.type = type;
        this.status = NotificationStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void markSent() {
        this.status = NotificationStatus.SENT;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
