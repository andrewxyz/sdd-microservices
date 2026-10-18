-- RefreshTokenRecord: one login's session, independent of every other session on the same account
-- (data-model.md RefreshTokenRecord; spec.md Clarifications 2026-10-18).
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    account_id  UUID NOT NULL REFERENCES accounts (id),
    token_hash  VARCHAR(64) NOT NULL,
    issued_at   TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ NULL,
    revoked_at  TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX refresh_tokens_token_hash_idx ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_account_id_idx ON refresh_tokens (account_id);
