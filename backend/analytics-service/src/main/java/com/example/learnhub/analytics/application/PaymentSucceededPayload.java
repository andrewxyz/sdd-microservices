package com.example.learnhub.analytics.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code EventEnvelope.payload} shape for {@code type = "payment.succeeded"}, version 1
 * (specs/004-checkout-enrollment/contracts/payment-succeeded.event.schema.json). Not a shared type
 * (constitution Principle II): analytics-service keeps its own matching copy, pinned to the schema by
 * {@code PaymentSucceededPayloadSchemaTest}. Declares every property the schema defines, including the
 * ones this service never reads or stores, because {@code EventMapper} rejects unknown properties.
 */
public record PaymentSucceededPayload(UUID paymentId, UUID userId, UUID courseId, int amountCents,
        String currency, Instant occurredAt) {
}
