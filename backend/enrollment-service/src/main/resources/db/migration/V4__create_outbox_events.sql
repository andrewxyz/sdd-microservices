-- specs/005-progress-certificates-notifications: enrollment-service's first outbox (research.md R7),
-- the same shape as payment-service's own (004) with enrollment_id in place of payment_id, plus
-- event_type since this outbox (unlike payment-service's single-event-type one) carries two different
-- routing keys: enrollment.created (research.md R2) and course.completed (research.md R1).
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,
    event_id      UUID NOT NULL,
    enrollment_id UUID NOT NULL REFERENCES enrollments(id),
    event_type    VARCHAR(50) NOT NULL,
    payload_json  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    published_at  TIMESTAMPTZ
);

CREATE INDEX outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
