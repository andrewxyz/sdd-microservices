package com.example.learnhub.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code EventEnvelope.payload} shape for {@code type = "enrollment.created"}, version 1
 * (specs/005-progress-certificates-notifications/contracts/enrollment-created.event.schema.json). Not
 * named in the /speckit-plan brief's own shared-events bullet; added because the brief's
 * notification-service bullet requires consuming it (research.md R2). Published by enrollment-service's
 * existing {@code PaymentSucceededListener} (specs/004), extended to also outbox this event; consumed by
 * notification-service.
 */
public record EnrollmentCreatedPayload(UUID enrollmentId, UUID userId, UUID courseId, Instant enrolledAt) {
}
