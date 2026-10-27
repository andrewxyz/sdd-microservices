package com.example.learnhub.analytics.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The ids of events already counted; the only per-event record, with no personal data. */
@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbc;

    public ProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event as processed. Returns 1 the first time and 0 when it was already recorded, so a
     * duplicate or replayed delivery is recognised atomically even under concurrency.
     */
    public int markProcessed(UUID eventId) {
        return jdbc.update("""
                INSERT INTO processed_events (event_id, processed_at) VALUES (?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, Timestamp.from(Instant.now()));
    }
}
