-- FEAT-005 operator identity: separate per-person accounts, TOTP MFA, short-lived sessions,
-- roles and append-only audit. No phone numbers and no balances are stored here.

CREATE TABLE operators (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    totp_secret_ciphertext BYTEA NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING_ENROLLMENT',
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    last_totp_step BIGINT NOT NULL DEFAULT -1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT operators_username_format_check
        CHECK (username ~ '^[A-Za-z0-9._-]{3,64}$'),
    CONSTRAINT operators_status_check
        CHECK (status IN ('PENDING_ENROLLMENT', 'ACTIVE', 'DISABLED')),
    CONSTRAINT operators_failed_attempts_check CHECK (failed_attempts >= 0),
    CONSTRAINT operators_last_totp_step_check CHECK (last_totp_step >= -1)
);

CREATE UNIQUE INDEX operators_username_lower_key ON operators (lower(username));

CREATE TABLE operator_roles (
    operator_id UUID NOT NULL REFERENCES operators (id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    granted_by UUID REFERENCES operators (id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (operator_id, role),
    CONSTRAINT operator_roles_role_check
        CHECK (role IN ('REFUND_OPERATOR', 'RECONCILER', 'FINANCE_APPROVER', 'OPERATOR_ADMIN'))
);

CREATE TABLE operator_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL REFERENCES operators (id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT operator_sessions_token_hash_key UNIQUE (token_hash),
    CONSTRAINT operator_sessions_expiry_check CHECK (expires_at > created_at)
);

CREATE INDEX idx_operator_sessions_operator_id ON operator_sessions (operator_id);
CREATE INDEX idx_operator_sessions_expiry ON operator_sessions (expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE operator_mfa_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL REFERENCES operators (id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT operator_mfa_challenges_token_hash_key UNIQUE (token_hash),
    CONSTRAINT operator_mfa_challenges_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT operator_mfa_challenges_attempts_check CHECK (attempts >= 0),
    CONSTRAINT operator_mfa_challenges_max_attempts_check CHECK (max_attempts > 0)
);

CREATE INDEX idx_operator_mfa_challenges_operator_id
    ON operator_mfa_challenges (operator_id);

CREATE TABLE operator_recovery_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL REFERENCES operators (id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at TIMESTAMPTZ,
    CONSTRAINT operator_recovery_codes_code_hash_key UNIQUE (code_hash)
);

CREATE INDEX idx_operator_recovery_codes_operator_id
    ON operator_recovery_codes (operator_id);

CREATE TABLE operator_auth_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID REFERENCES operators (id),
    event TEXT NOT NULL,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_operator_auth_events_operator_id
    ON operator_auth_events (operator_id, created_at);

CREATE TABLE operator_actions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id UUID NOT NULL REFERENCES operators (id),
    action TEXT NOT NULL,
    subject_type TEXT,
    subject_id UUID,
    reason TEXT,
    request_id TEXT,
    before_state TEXT,
    after_state TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_operator_actions_operator_id ON operator_actions (operator_id, created_at);
CREATE INDEX idx_operator_actions_subject ON operator_actions (subject_type, subject_id);

CREATE TRIGGER operator_auth_events_append_only
BEFORE UPDATE OR DELETE ON operator_auth_events
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER operator_actions_append_only
BEFORE UPDATE OR DELETE ON operator_actions
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
