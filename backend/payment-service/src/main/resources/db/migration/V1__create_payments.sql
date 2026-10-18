-- One attempt to pay for one course by one user (data-model.md "Payment").
CREATE TABLE payments (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    course_id       UUID NOT NULL,
    amount_cents    INTEGER NOT NULL CHECK (amount_cents >= 0),
    currency        VARCHAR(3) NOT NULL DEFAULT 'USD',
    status          VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    payment_token   VARCHAR(255),
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

-- research.md R9: resending the same checkout under the same idempotency key must always resolve to
-- the same stored row rather than a second one.
CREATE UNIQUE INDEX payments_user_idempotency_key_unique ON payments (user_id, idempotency_key);

-- research.md R3, FR-004, FR-015: a user can never hold more than one succeeded payment for the same
-- course. Partial (status-scoped) so a declined or failed attempt never blocks a later, successful one.
CREATE UNIQUE INDEX payments_one_succeeded_per_user_course ON payments (user_id, course_id)
    WHERE status = 'SUCCEEDED';
