package com.example.learnhub.gateway.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * specs/005-progress-certificates-notifications research.md R8: a second, stricter bucket for routes
 * whose metadata carries {@code rate-limit: strict} (currently just certificate verification) —
 * independent of, and lower than, the platform's general {@link RateLimiterProperties} allowance.
 */
@ConfigurationProperties("platform.gateway.rate-limit-strict")
public class StrictRateLimiterProperties implements RateLimitSettings {

    /** Deliberately much lower than the general bucket's: a public, guessable-code endpoint. */
    private int capacity = 10;

    private int refillPerSecond = 2;

    private Duration idleEviction = Duration.ofMinutes(10);

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getRefillPerSecond() {
        return refillPerSecond;
    }

    public void setRefillPerSecond(int refillPerSecond) {
        this.refillPerSecond = refillPerSecond;
    }

    public Duration getIdleEviction() {
        return idleEviction;
    }

    public void setIdleEviction(Duration idleEviction) {
        this.idleEviction = idleEviction;
    }
}
