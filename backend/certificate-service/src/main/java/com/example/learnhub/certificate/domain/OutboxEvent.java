package com.example.learnhub.certificate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The durable record that a {@code certificate.issued} event still needs to be published (research.md
 * R6, R7). Identical shape to payment-service's own {@code OutboxEvent} (004), with
 * {@code certificateId} in place of {@code paymentId}.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "certificate_id", nullable = false, updatable = false)
    private UUID certificateId;

    @Column(name = "payload_json", nullable = false, updatable = false)
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(UUID id, UUID eventId, UUID certificateId, String payloadJson, Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.certificateId = certificateId;
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

    public UUID getCertificateId() {
        return certificateId;
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
