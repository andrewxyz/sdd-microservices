package com.example.learnhub.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code EventEnvelope.payload} shape for {@code type = "certificate.issued"}, version 1
 * (specs/005-progress-certificates-notifications/contracts/certificate-issued.event.schema.json).
 * Published by certificate-service; consumed by notification-service. Deliberately carries no course
 * title — notification-service fetches it fresh from course-service itself (research.md R3, R5).
 */
public record CertificateIssuedPayload(UUID certificateId, UUID userId, UUID courseId,
        String verificationCode, Instant issuedAt) {
}
