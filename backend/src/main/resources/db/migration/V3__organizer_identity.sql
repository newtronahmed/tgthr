-- FEAT-003 organizer identity and goal creation. Accounts, OTP challenges and opaque sessions
-- are separate from the public contribution path; goals gain a creator, a locked payout
-- destination and an idempotency key. No balances and no ledger rows are added here.

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_ciphertext BYTEA NOT NULL,
    phone_hash BYTEA NOT NULL,
    display_name TEXT,
    legal_name TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_phone_hash_key UNIQUE (phone_hash)
);

CREATE TABLE user_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users (id),
    token_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT user_sessions_token_hash_key UNIQUE (token_hash),
    CONSTRAINT user_sessions_expiry_check CHECK (expires_at > created_at)
);

CREATE INDEX idx_user_sessions_user_id ON user_sessions (user_id);

CREATE TABLE otp_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_hash BYTEA NOT NULL,
    code_hash BYTEA NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT otp_challenges_attempts_check CHECK (attempts >= 0),
    CONSTRAINT otp_challenges_max_attempts_check CHECK (max_attempts > 0)
);

CREATE INDEX idx_otp_challenges_active
    ON otp_challenges (phone_hash, created_at DESC)
    WHERE consumed_at IS NULL;

CREATE INDEX idx_otp_challenges_phone_created
    ON otp_challenges (phone_hash, created_at DESC);

ALTER TABLE goals
    ADD COLUMN created_by_user_id UUID REFERENCES users (id),
    ADD COLUMN destination_phone_ciphertext BYTEA,
    ADD COLUMN destination_phone_hash BYTEA,
    ADD COLUMN destination_network TEXT,
    ADD COLUMN destination_account_name TEXT,
    ADD COLUMN destination_locked_at TIMESTAMPTZ,
    ADD COLUMN second_approver_threshold_pesewas BIGINT NOT NULL DEFAULT 50000,
    ADD COLUMN create_idempotency_key TEXT;

ALTER TABLE goals
    ADD CONSTRAINT goals_destination_network_check
        CHECK (destination_network IS NULL OR destination_network IN ('MTN', 'TELECEL', 'AT')),
    ADD CONSTRAINT goals_second_approver_threshold_check
        CHECK (second_approver_threshold_pesewas >= 0),
    ADD CONSTRAINT goals_creator_destination_check
        CHECK (created_by_user_id IS NULL
            OR (destination_phone_ciphertext IS NOT NULL
                AND destination_phone_hash IS NOT NULL
                AND destination_network IS NOT NULL
                AND destination_account_name IS NOT NULL
                AND destination_locked_at IS NOT NULL
                AND create_idempotency_key IS NOT NULL));

CREATE UNIQUE INDEX goals_creator_idempotency_key
    ON goals (created_by_user_id, create_idempotency_key);

CREATE INDEX idx_goals_created_by_user_id ON goals (created_by_user_id, created_at DESC);

CREATE TABLE goal_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    goal_id UUID NOT NULL REFERENCES goals (id),
    event TEXT NOT NULL,
    from_status TEXT,
    to_status TEXT NOT NULL,
    actor_user_id UUID REFERENCES users (id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail JSONB
);

CREATE INDEX idx_goal_events_goal_id ON goal_events (goal_id, occurred_at);

CREATE TRIGGER goal_events_append_only
BEFORE UPDATE OR DELETE ON goal_events
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER goal_events_no_truncate
BEFORE TRUNCATE ON goal_events
FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_truncate();
