-- FEAT-005 operator administration: one-time enrollment tokens for admin-created accounts.
-- No password, TOTP secret or token is stored in a migration; only hashes live here.

CREATE TABLE operator_enrollment_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL REFERENCES operators (id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    password_set_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT operator_enrollment_tokens_token_hash_key UNIQUE (token_hash),
    CONSTRAINT operator_enrollment_tokens_expiry_check CHECK (expires_at > created_at)
);

CREATE UNIQUE INDEX operator_enrollment_tokens_open_key
    ON operator_enrollment_tokens (operator_id)
    WHERE consumed_at IS NULL;

CREATE INDEX idx_operator_enrollment_tokens_operator_id
    ON operator_enrollment_tokens (operator_id);
