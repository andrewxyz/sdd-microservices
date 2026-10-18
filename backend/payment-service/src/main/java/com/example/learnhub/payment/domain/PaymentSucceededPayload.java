package com.example.learnhub.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code EventEnvelope.payload} shape for {@code type = "payment.succeeded"}, version 1
 * (data-model.md, contracts/payment-succeeded.event.schema.json). Not a shared type (constitution
 * Principle II) — enrollment-service keeps its own matching copy on the consuming side.
 */
public record PaymentSucceededPayload(UUID paymentId, UUID userId, UUID courseId, int amountCents,
        String currency, Instant occurredAt) {

    public static PaymentSucceededPayload of(Payment payment) {
        return new PaymentSucceededPayload(payment.getId(), payment.getUserId(), payment.getCourseId(),
                payment.getAmountCents(), payment.getCurrency(), payment.getUpdatedAt());
    }
}
