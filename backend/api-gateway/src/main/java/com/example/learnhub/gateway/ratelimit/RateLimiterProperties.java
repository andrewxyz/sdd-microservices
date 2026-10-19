package com.example.learnhub.gateway.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate limit settings, read from central configuration so they can be tuned without a rebuild
 * (FR-024). The defaults come from research R8.
 */
@ConfigurationProperties("platform.gateway.rate-limit")
public class RateLimiterProperties implements RateLimitSettings {

    /**
     * Burst size per client address. 200 is high enough that the smoke checks, which make 100 calls
     * from one address, do not trip it, and low enough that a concurrent burst does.
     */
    private int capacity = 200;

    /** Sustained requests per second per client address, once the burst is spent. */
    private int refillPerSecond = 100;

    /** Buckets untouched for this long are dropped, so memory does not grow with client count. */
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
