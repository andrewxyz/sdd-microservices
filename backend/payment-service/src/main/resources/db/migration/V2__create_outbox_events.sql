-- The durable record that a payment.succeeded event still needs to be published (research.md R1,
-- data-model.md "OutboxEvent"). Written in the same transaction as the owning payment row's update to
-- SUCCEEDED, so the two facts can never become inconsistent with each other.
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,
    event_id      UUID NOT NULL,
    payment_id    UUID NOT NULL REFERENCES payments(id),
    payload_json  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    published_at  TIMESTAMPTZ
);

-- The publisher polls for exactly this.
CREATE INDEX outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
