package com.example.learnhub.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** {@code POST /api/v1/checkouts} request body (FR-001, FR-010). */
public record CheckoutRequest(
        @NotNull UUID courseId,
        @NotBlank String idempotencyKey,
        String paymentToken) {
}
