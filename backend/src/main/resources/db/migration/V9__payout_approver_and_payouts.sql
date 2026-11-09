-- FEAT-002 payout foundation: approver settings on the goal and the payout records used by the
-- reservation, approval, visibility, notification and execution slices. No balance is stored;
-- the ledger remains the source of truth.

ALTER TABLE goals
    ADD COLUMN second_approver_name TEXT,
    ADD COLUMN second_approver_phone_ciphertext BYTEA,
    ADD COLUMN second_approver_phone_hash BYTEA,
    ADD COLUMN second_approver_set_at TIMESTAMPTZ;

ALTER TABLE goals
    ADD CONSTRAINT goals_second_approver_identity_check
        CHECK (
            (second_approver_name IS NULL
                AND second_approver_phone_ciphertext IS NULL
                AND second_approver_phone_hash IS NULL
                AND second_approver_set_at IS NULL)
            OR
            (second_approver_name IS NOT NULL
                AND second_approver_phone_ciphertext IS NOT NULL
                AND second_approver_phone_hash IS NOT NULL
                AND second_approver_set_at IS NOT NULL)
        );

CREATE INDEX idx_goals_second_approver_phone_hash
    ON goals (second_approver_phone_hash)
    WHERE second_approver_phone_hash IS NOT NULL;

CREATE TABLE payouts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id UUID NOT NULL REFERENCES goals (id),
    requested_by UUID NOT NULL REFERENCES users (id),
    amount_minor_units BIGINT NOT NULL,
    fee_minor_units BIGINT NOT NULL DEFAULT 0,
    net_minor_units BIGINT NOT NULL,
    currency_code TEXT NOT NULL DEFAULT 'GHS',
    state TEXT NOT NULL DEFAULT 'REQUESTED',
    idempotency_key TEXT NOT NULL,
    provider_reference TEXT,
    failure_code TEXT,
    approved_at TIMESTAMPTZ,
    hold_expires_at TIMESTAMPTZ,
    executing_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payouts_amount_positive_check CHECK (amount_minor_units > 0),
    CONSTRAINT payouts_fee_nonnegative_check CHECK (fee_minor_units >= 0),
    CONSTRAINT payouts_fee_less_than_amount_check CHECK (fee_minor_units < amount_minor_units),
    CONSTRAINT payouts_net_check CHECK (net_minor_units = amount_minor_units - fee_minor_units),
    CONSTRAINT payouts_currency_check CHECK (currency_code = 'GHS'),
    CONSTRAINT payouts_state_check
        CHECK (state IN
            ('REQUESTED', 'APPROVED', 'HOLD', 'READY', 'EXECUTING', 'COMPLETED', 'FAILED',
             'CANCELLED')),
    CONSTRAINT payouts_goal_idempotency_key UNIQUE (goal_id, idempotency_key),
    CONSTRAINT payouts_provider_reference_key UNIQUE (provider_reference)
);

CREATE INDEX idx_payouts_goal_state ON payouts (goal_id, state);
CREATE INDEX idx_payouts_state_updated_at ON payouts (state, updated_at);

CREATE TABLE payout_approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id UUID NOT NULL REFERENCES payouts (id),
    token_hash BYTEA NOT NULL,
    code_hash BYTEA NOT NULL,
    token_expires_at TIMESTAMPTZ NOT NULL,
    code_expires_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payout_approvals_token_hash_key UNIQUE (token_hash),
    CONSTRAINT payout_approvals_attempts_check CHECK (attempts >= 0),
    CONSTRAINT payout_approvals_max_attempts_check CHECK (max_attempts > 0),
    CONSTRAINT payout_approvals_expiry_check CHECK (token_expires_at > created_at)
);

CREATE INDEX idx_payout_approvals_payout ON payout_approvals (payout_id, created_at DESC);
CREATE UNIQUE INDEX payout_approvals_one_active
    ON payout_approvals (payout_id)
    WHERE consumed_at IS NULL;

-- Defense in depth for the settings lock: even if a caller bypasses the service check, an update
-- cannot change approver/hold settings after a payout exists. The payout writer must still take the
-- goal row lock before insert; this trigger covers the sequential case.
CREATE FUNCTION protect_goal_payout_settings() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM payouts WHERE goal_id = NEW.id)
        AND (
            OLD.second_approver_name IS DISTINCT FROM NEW.second_approver_name
            OR OLD.second_approver_phone_ciphertext
                IS DISTINCT FROM NEW.second_approver_phone_ciphertext
            OR OLD.second_approver_phone_hash IS DISTINCT FROM NEW.second_approver_phone_hash
            OR OLD.second_approver_set_at IS DISTINCT FROM NEW.second_approver_set_at
            OR OLD.hold_window_minutes IS DISTINCT FROM NEW.hold_window_minutes
        )
    THEN
        RAISE EXCEPTION 'payout settings are locked'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER goals_payout_settings_locked
BEFORE UPDATE ON goals
FOR EACH ROW EXECUTE FUNCTION protect_goal_payout_settings();

CREATE TABLE payout_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id UUID NOT NULL REFERENCES payouts (id),
    contribution_id UUID REFERENCES contributions (id),
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payout_reports_reason_check CHECK (length(btrim(reason)) > 0)
);

CREATE INDEX idx_payout_reports_payout ON payout_reports (payout_id, created_at DESC);

CREATE TABLE payout_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payout_id UUID NOT NULL REFERENCES payouts (id),
    contribution_id UUID NOT NULL REFERENCES contributions (id),
    payer_phone_hash BYTEA NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payout_notifications_payout_contributor_key
        UNIQUE (payout_id, payer_phone_hash),
    CONSTRAINT payout_notifications_payout_contribution_key
        UNIQUE (payout_id, contribution_id)
);

CREATE INDEX idx_payout_notifications_payout ON payout_notifications (payout_id);

CREATE TABLE payout_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payout_id UUID NOT NULL REFERENCES payouts (id),
    event TEXT NOT NULL,
    from_state TEXT,
    to_state TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail JSONB
);

CREATE INDEX idx_payout_events_payout ON payout_events (payout_id, occurred_at);

CREATE TRIGGER payout_events_append_only
BEFORE UPDATE OR DELETE ON payout_events
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER payout_events_no_truncate
BEFORE TRUNCATE ON payout_events
FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_truncate();
