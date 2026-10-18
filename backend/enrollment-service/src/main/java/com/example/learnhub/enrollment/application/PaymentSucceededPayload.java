package com.example.learnhub.enrollment.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code EventEnvelope.payload} shape for {@code type = "payment.succeeded"}, version 1
 * (contracts/payment-succeeded.event.schema.json). Not a shared type (constitution Principle II) —
 * payment-service keeps its own matching copy on the publishing side. Declares every field the schema
 * defines, even the ones this service does not use, because {@code EventMapper}'s mapper rejects
 * unknown properties.
 */
public record PaymentSucceededPayload(UUID paymentId, UUID userId, UUID courseId, int amountCents,
        String currency, Instant occurredAt) {
}
