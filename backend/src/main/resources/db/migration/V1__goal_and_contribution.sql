-- FEAT-001 data model: goals, contributions and their append-only history.
-- Money is integer pesewas (Constitution I.3); no balances are stored.
-- Append-only enforcement for contribution_events arrives with V2 (T040).

CREATE TABLE goals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug TEXT NOT NULL,
    title TEXT NOT NULL,
    template TEXT NOT NULL,
    target_type TEXT NOT NULL,
    fixed_amount_pesewas BIGINT,
    target_amount_pesewas BIGINT,
    minimum_amount_pesewas BIGINT NOT NULL DEFAULT 1000,
    deadline TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'OPEN',
    destination_type TEXT NOT NULL,
    second_approver TEXT NOT NULL,
    hold_window_minutes INTEGER NOT NULL,
    surplus_rule TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT goals_slug_key UNIQUE (slug),
    CONSTRAINT goals_template_check
        CHECK (template IN ('TRIP', 'WEDDING', 'FUNERAL', 'RENT', 'COMMUNITY', 'GIFT', 'PARTY')),
    CONSTRAINT goals_target_type_check CHECK (target_type IN ('FIXED', 'OPEN')),
    CONSTRAINT goals_fixed_amount_check
        CHECK (target_type <> 'FIXED' OR fixed_amount_pesewas IS NOT NULL),
    CONSTRAINT goals_fixed_amount_positive_check
        CHECK (fixed_amount_pesewas IS NULL OR fixed_amount_pesewas > 0),
    CONSTRAINT goals_target_amount_positive_check
        CHECK (target_amount_pesewas IS NULL OR target_amount_pesewas > 0),
    CONSTRAINT goals_minimum_amount_floor_check CHECK (minimum_amount_pesewas >= 1000),
    CONSTRAINT goals_status_check CHECK (status IN ('OPEN', 'CLOSED', 'FROZEN')),
    CONSTRAINT goals_destination_type_check
        CHECK (destination_type IN ('BENEFICIARY_MOMO', 'ADMIN_MOMO')),
    CONSTRAINT goals_second_approver_check
        CHECK (second_approver IN ('OFF', 'ABOVE_THRESHOLD', 'ALWAYS')),
    CONSTRAINT goals_hold_window_check CHECK (hold_window_minutes >= 0),
    CONSTRAINT goals_surplus_rule_check
        CHECK (surplus_rule IN ('PAY_BENEFICIARY', 'WELFARE_RESERVE'))
);

CREATE TABLE contributions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goal_id UUID NOT NULL REFERENCES goals (id),
    amount_pesewas BIGINT NOT NULL,
    processing_fee_pesewas BIGINT NOT NULL,
    platform_fee_pesewas BIGINT NOT NULL,
    total_pesewas BIGINT NOT NULL,
    state TEXT NOT NULL DEFAULT 'INITIATED',
    provider_reference TEXT,
    payer_phone_ciphertext BYTEA NOT NULL,
    payer_phone_hash BYTEA NOT NULL,
    display_name TEXT,
    anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    idempotency_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT contributions_provider_reference_key UNIQUE (provider_reference),
    CONSTRAINT contributions_idempotency_key_key UNIQUE (idempotency_key),
    CONSTRAINT contributions_amount_positive_check CHECK (amount_pesewas > 0),
    CONSTRAINT contributions_processing_fee_check CHECK (processing_fee_pesewas >= 0),
    CONSTRAINT contributions_platform_fee_check CHECK (platform_fee_pesewas >= 0),
    CONSTRAINT contributions_total_positive_check CHECK (total_pesewas > 0),
    CONSTRAINT contributions_total_breakdown_check
        CHECK (total_pesewas = amount_pesewas + processing_fee_pesewas + platform_fee_pesewas),
    CONSTRAINT contributions_state_check
        CHECK (state IN ('INITIATED', 'PENDING', 'CONFIRMED', 'FAILED', 'EXPIRED', 'REFUNDED'))
);

CREATE INDEX idx_contributions_goal_id ON contributions (goal_id);
CREATE INDEX idx_contributions_payer_phone_hash ON contributions (payer_phone_hash);
CREATE INDEX idx_contributions_pending_created_at
    ON contributions (created_at)
    WHERE state = 'PENDING';

CREATE TABLE contribution_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contribution_id UUID NOT NULL REFERENCES contributions (id),
    from_state TEXT,
    to_state TEXT NOT NULL,
    event TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail JSONB
);

CREATE INDEX idx_contribution_events_contribution_id
    ON contribution_events (contribution_id, occurred_at);
