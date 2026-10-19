package com.example.learnhub.gateway.ratelimit;

import java.time.Duration;

/**
 * The three numbers a {@link TokenBucketRateLimiter} needs, implemented by both
 * {@link RateLimiterProperties} (the general bucket) and {@link StrictRateLimiterProperties} (the
 * stricter, route-scoped bucket from specs/005-progress-certificates-notifications research.md R8) so
 * one limiter class serves both without duplicating its logic.
 */
public interface RateLimitSettings {

    int getCapacity();

    int getRefillPerSecond();

    Duration getIdleEviction();
}
