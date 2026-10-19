package com.example.learnhub.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The durable record that an {@code enrollment.created} or {@code course.completed} event still needs
 * publishing (research.md R1, R2, R7). The same shape as payment-service's own {@code OutboxEvent}
 * (004) with {@code enrollmentId} in place of {@code paymentId}, plus {@code eventType} — unlike
 * payment-service's single-event-type outbox, this one carries two different routing keys.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "enrollment_id", nullable = false, updatable = false)
    private UUID enrollmentId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "payload_json", nullable = false, updatable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(UUID id, UUID eventId, UUID enrollmentId, String eventType, String payloadJson,
            Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.enrollmentId = enrollmentId;
        this.eventType = eventType;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
    }

    public void markPublished(Instant now) {
        this.publishedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getEnrollmentId() {
        return enrollmentId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
