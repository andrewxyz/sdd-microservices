-- The durable record that a certificate.issued event still needs to be published (research.md R6, R7).
-- Identical shape to payment-service's own (004), with certificate_id in place of payment_id.
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY,
    event_id       UUID NOT NULL,
    certificate_id UUID NOT NULL REFERENCES certificates(id),
    payload_json   TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    published_at   TIMESTAMPTZ
);

CREATE INDEX outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
