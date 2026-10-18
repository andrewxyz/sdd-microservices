package com.example.learnhub.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** FR-027: the exchange name and the routing-key naming rule, with no domain keys defined here. */
class RoutingKeysTest {

    @Test
    @DisplayName("the single topic exchange is learning.events")
    void exchangeName() {
        assertThat(LearningEvents.EXCHANGE).isEqualTo("learning.events");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment.succeeded", "course.published", "enrollment.created", "review.flagged_v2"})
    @DisplayName("valid <domain>.<event> keys are accepted")
    void acceptsValidKeys(String key) {
        assertThat(RoutingKeys.isValid(key)).as(key).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Payment.Succeeded",
            "payment",
            "payment.",
            ".succeeded",
            "payment.succeeded.now",
            "payment succeeded",
            "payment-succeeded",
            "1payment.succeeded",
            ""
    })
    @DisplayName("keys that break the naming rule are rejected")
    void rejectsInvalidKeys(String key) {
        assertThat(RoutingKeys.isValid(key)).as(key).isFalse();
    }

    @Test
    @DisplayName("null is rejected rather than throwing")
    void rejectsNull() {
        assertThat(RoutingKeys.isValid(null)).isFalse();
    }
}
