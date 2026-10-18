package com.example.learnhub.auth.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory, single-instance login-attempt throttling, keyed independently by normalized email and by
 * source IP (FR-008, SC-005, research.md R3, data-model.md LoginAttemptWindow). A key at or above
 * {@value #THRESHOLD} failures inside a {@value #WINDOW_SECONDS}-second rolling window is throttled.
 */
@Component
public class LoginAttemptWindow {

    private static final int THRESHOLD = 5;
    private static final long WINDOW_SECONDS = 60;

    private final Clock clock;
    private final Map<String, List<Instant>> failuresByKey = new ConcurrentHashMap<>();

    public LoginAttemptWindow() {
        this(Clock.systemUTC());
    }

    public LoginAttemptWindow(Clock clock) {
        this.clock = clock;
    }

    /** True if this key has already reached the threshold within the current window. */
    public boolean isThrottled(String key) {
        return recentFailures(key).size() >= THRESHOLD;
    }

    /** Records a failed attempt for this key. */
    public void recordFailure(String key) {
        failuresByKey.compute(key, (k, existing) -> {
            List<Instant> updated = new java.util.ArrayList<>(recentFailures(k));
            updated.add(clock.instant());
            return updated;
        });
    }

    /** Clears this key's window, e.g. after a successful login for that email. */
    public void clear(String key) {
        failuresByKey.remove(key);
    }

    /** Test-only: every MockMvc request shares one remote address, so tests must reset between cases. */
    public void clearAll() {
        failuresByKey.clear();
    }

    private List<Instant> recentFailures(String key) {
        List<Instant> existing = failuresByKey.get(key);
        if (existing == null) {
            return List.of();
        }
        Instant cutoff = clock.instant().minus(Duration.ofSeconds(WINDOW_SECONDS));
        return existing.stream().filter(instant -> instant.isAfter(cutoff)).toList();
    }
}
