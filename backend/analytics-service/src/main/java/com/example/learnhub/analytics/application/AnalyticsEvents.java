package com.example.learnhub.analytics.application;

/** Routing keys analytics-service consumes that {@code LearningEvents} does not define. */
public final class AnalyticsEvents {

    /** Published by payment-service on {@code learning.events}; shared-events has no constant for it. */
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";

    private AnalyticsEvents() {
    }
}
