-- Account: a registered user of the platform (data-model.md Account).
CREATE TABLE accounts (
    id            UUID PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20) NOT NULL CHECK (role IN ('STUDENT', 'INSTRUCTOR', 'ADMINISTRATOR')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness (FR-002): "A@x.com" and "a@x.com" collide.
CREATE UNIQUE INDEX accounts_email_lower_idx ON accounts (lower(email));
