package com.example.learnhub.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-024 and clarification Q1: the limit is per client network address, with no Redis.
 *
 * <p>The clock is injected so refill can be tested without waiting, which also keeps this test from
 * being timing-flaky on a loaded machine.
 */
class TokenBucketRateLimiterTest {

    private AtomicReference<Instant> now;
    private RateLimiterProperties properties;
    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void setUp() {
        now = new AtomicReference<>(Instant.parse("2026-10-17T10:00:00Z"));
        properties = new RateLimiterProperties();
        properties.setCapacity(5);
        properties.setRefillPerSecond(2);
        properties.setIdleEviction(Duration.ofMinutes(10));
        limiter = new TokenBucketRateLimiter(properties, now::get);
    }

    private void advance(Duration amount) {
        now.set(now.get().plus(amount));
    }

    @Test
    @DisplayName("requests are allowed up to the capacity, then refused")
    void allowsUpToCapacityThenRefuses() {
        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.tryConsume("10.0.0.1")).as("request %d", i).isTrue();
        }

        assertThat(limiter.tryConsume("10.0.0.1")).as("the sixth request").isFalse();
    }

    @Test
    @DisplayName("tokens come back over time at the configured rate")
    void refillsOverTime() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume("10.0.0.1");
        }
        assertThat(limiter.tryConsume("10.0.0.1")).isFalse();

        advance(Duration.ofSeconds(1));   // 2 per second

        assertThat(limiter.tryConsume("10.0.0.1")).isTrue();
        assertThat(limiter.tryConsume("10.0.0.1")).isTrue();
        assertThat(limiter.tryConsume("10.0.0.1")).as("only two were refilled").isFalse();
    }

    @Test
    @DisplayName("refill never exceeds the capacity")
    void refillStopsAtCapacity() {
        limiter.tryConsume("10.0.0.1");
        advance(Duration.ofHours(1));

        for (int i = 1; i <= 5; i++) {
            assertThat(limiter.tryConsume("10.0.0.1")).as("request %d", i).isTrue();
        }
        assertThat(limiter.tryConsume("10.0.0.1")).as("capacity is still 5, not more").isFalse();
    }

    @Test
    @DisplayName("two addresses are limited separately, so one noisy client cannot starve another")
    void addressesAreIndependent() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume("10.0.0.1");
        }
        assertThat(limiter.tryConsume("10.0.0.1")).isFalse();

        assertThat(limiter.tryConsume("10.0.0.2")).as("a different address is unaffected").isTrue();
    }

    @Test
    @DisplayName("clients sharing one address share one bucket, as the clarification requires")
    void sameAddressSharesOneBucket() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("10.0.0.9")).isTrue();
        }

        // A second client behind the same address finds the bucket already empty.
        assertThat(limiter.tryConsume("10.0.0.9")).isFalse();
    }

    @Test
    @DisplayName("idle buckets are evicted so memory does not grow without bound")
    void evictsIdleBuckets() {
        limiter.tryConsume("10.0.0.1");
        limiter.tryConsume("10.0.0.2");
        assertThat(limiter.bucketCount()).isEqualTo(2);

        advance(Duration.ofMinutes(11));
        limiter.tryConsume("10.0.0.3");

        assertThat(limiter.bucketCount()).as("the two idle buckets are gone, the new one remains").isEqualTo(1);
    }

    @Test
    @DisplayName("a bucket still in use is not evicted")
    void keepsActiveBuckets() {
        limiter.tryConsume("10.0.0.1");
        advance(Duration.ofMinutes(6));
        limiter.tryConsume("10.0.0.1");
        advance(Duration.ofMinutes(6));
        limiter.tryConsume("10.0.0.2");

        assertThat(limiter.bucketCount()).as("10.0.0.1 was used 6 minutes ago, inside the 10 minute window")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("changing the limit centrally clears the buckets, so the new limit applies at once")
    void resetAppliesNewLimitImmediately() {
        for (int i = 0; i < 5; i++) {
            limiter.tryConsume("10.0.0.1");
        }
        assertThat(limiter.tryConsume("10.0.0.1")).isFalse();

        properties.setCapacity(10);
        limiter.reset();

        assertThat(limiter.tryConsume("10.0.0.1")).as("the raised limit takes effect without a restart").isTrue();
        assertThat(limiter.bucketCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the defaults are the values chosen in research R8")
    void defaults() {
        RateLimiterProperties defaults = new RateLimiterProperties();

        assertThat(defaults.getCapacity()).isEqualTo(200);
        assertThat(defaults.getRefillPerSecond()).isEqualTo(100);
        assertThat(defaults.getIdleEviction()).isEqualTo(Duration.ofMinutes(10));
    }
}
