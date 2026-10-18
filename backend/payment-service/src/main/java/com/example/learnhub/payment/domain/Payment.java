package com.example.learnhub.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to pay for one course by one user (data-model.md "Payment"). Immutable once resolved to
 * {@link PaymentStatus#SUCCEEDED}, {@link PaymentStatus#DECLINED} or {@link PaymentStatus#FAILED} —
 * refunds and retries are out of scope (spec.md); a new attempt is a new row under a new idempotency
 * key.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(name = "amount_cents", nullable = false)
    private int amountCents;

    @Column(nullable = false)
    private String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "payment_token", updatable = false)
    private String paymentToken;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // JPA
    }

    public Payment(UUID id, UUID userId, UUID courseId, String idempotencyKey, String paymentToken,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.courseId = courseId;
        this.idempotencyKey = idempotencyKey;
        this.paymentToken = paymentToken;
        this.status = PaymentStatus.PENDING;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void resolveSucceeded(int amountCents, Instant now) {
        this.amountCents = amountCents;
        this.status = PaymentStatus.SUCCEEDED;
        this.updatedAt = now;
    }

    public void resolveDeclined(String reason, Instant now) {
        this.status = PaymentStatus.DECLINED;
        this.failureReason = reason;
        this.updatedAt = now;
    }

    public void resolveFailed(String reason, Instant now) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public int getAmountCents() {
        return amountCents;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPaymentToken() {
        return paymentToken;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
