-- One attempt to turn a consumed event into an email (data-model.md "Notification"). event_id's
-- uniqueness IS notification-service's idempotency guard (FR-022) -- not a separate ledger table.
CREATE TABLE notifications (
    id          UUID PRIMARY KEY,
    event_id    UUID NOT NULL,
    user_id     UUID NOT NULL,
    type        VARCHAR(20) NOT NULL,
    status      VARCHAR(10) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX notifications_event_id_unique ON notifications (event_id);
