package com.example.learnhub.payment.domain;

/** FR-006 to FR-012: a payment's lifecycle state. Once resolved, a row never changes status again. */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    DECLINED,
    FAILED
}
