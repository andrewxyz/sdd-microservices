package com.example.learnhub.gateway.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * An in-memory token bucket per client address (FR-024, clarification Q1).
 *
 * <p>In memory and not Redis because the platform runs a single gateway instance; high availability
 * is out of scope for this feature. If a second gateway instance is ever added, each would hold its
 * own buckets and the effective limit would double, so that change needs a shared store.
 *
 * <p>The clock is injected so refill and eviction can be tested without sleeping.
 */
public class TokenBucketRateLimiter {

    private final RateLimiterProperties properties;
    private final Supplier<Instant> clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(RateLimiterProperties properties, Supplier<Instant> clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Takes one token for {@code clientKey}; false means the client is over its limit. */
    public boolean tryConsume(String clientKey) {
        Instant now = clock.get();
        evictIdle(now);

        Bucket bucket = buckets.computeIfAbsent(clientKey,
                key -> new Bucket(properties.getCapacity(), now));

        synchronized (bucket) {
            bucket.refill(now, properties.getCapacity(), properties.getRefillPerSecond());
            bucket.lastSeen = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return true;
            }
            return false;
        }
    }

    /** Seconds until at least one token is available again, for the Retry-After header. */
    public long retryAfterSeconds(String clientKey) {
        Bucket bucket = buckets.get(clientKey);
        if (bucket == null || properties.getRefillPerSecond() <= 0) {
            return 1;
        }
        synchronized (bucket) {
            double needed = 1 - bucket.tokens;
            return needed <= 0 ? 1 : Math.max(1, (long) Math.ceil(needed / properties.getRefillPerSecond()));
        }
    }

    /**
     * Drops every bucket. Called when the limits change centrally, so a raised or lowered limit takes
     * effect at once rather than after the old buckets happen to be evicted.
     */
    public void reset() {
        buckets.clear();
    }

    /** Visible for tests. */
    public int bucketCount() {
        return buckets.size();
    }

    private void evictIdle(Instant now) {
        Duration idle = properties.getIdleEviction();
        if (idle == null || idle.isZero() || idle.isNegative()) {
            return;
        }
        buckets.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return Duration.between(entry.getValue().lastSeen, now).compareTo(idle) > 0;
            }
        });
    }

    private static final class Bucket {
        private double tokens;
        private Instant lastRefill;
        private Instant lastSeen;

        private Bucket(int capacity, Instant now) {
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastSeen = now;
        }

        private void refill(Instant now, int capacity, int perSecond) {
            long elapsedMillis = Duration.between(lastRefill, now).toMillis();
            if (elapsedMillis <= 0) {
                return;
            }
            tokens = Math.min(capacity, tokens + (elapsedMillis / 1000.0) * perSecond);
            lastRefill = now;
        }
    }
}
