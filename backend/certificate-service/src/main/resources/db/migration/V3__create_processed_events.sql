-- Idempotency ledger for the course.completed consumer (research.md R6, constitution Principle V).
-- Identical shape to enrollment-service's own (004).
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
