package com.example.learnhub.payment.api;

import com.example.learnhub.payment.domain.Payment;
import com.example.learnhub.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/** The client-facing shape of a {@link Payment} (contracts/payment-service.openapi.yaml). */
public record PaymentView(UUID id, UUID courseId, int amountCents, String currency, String status,
        String failureReason, String message, Instant createdAt, Instant updatedAt) {

    public static PaymentView of(Payment payment) {
        String message = payment.getStatus() == PaymentStatus.SUCCEEDED
                ? "Enrollment will follow shortly."
                : null;
        return new PaymentView(payment.getId(), payment.getCourseId(), payment.getAmountCents(),
                payment.getCurrency(), payment.getStatus().name(), payment.getFailureReason(), message,
                payment.getCreatedAt(), payment.getUpdatedAt());
    }
}
