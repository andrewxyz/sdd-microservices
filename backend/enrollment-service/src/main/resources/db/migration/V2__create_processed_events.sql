-- Idempotency ledger for the payment.succeeded consumer (research.md R6, constitution Principle V).
-- A listener invocation that finds its event_id already here acknowledges and does nothing further.
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
