-- FEAT-005 refund core: refund records, append-only events, and the ledger link for the reversal
-- and retained-fee loss postings. No balance is stored; the reservation is derived from the refund
-- state and the ledger remains the source of truth.

CREATE TABLE refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contribution_id UUID NOT NULL REFERENCES contributions (id),
    goal_id UUID NOT NULL REFERENCES goals (id),
    source_account TEXT NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    platform_fee_minor_units BIGINT NOT NULL,
    processing_fee_minor_units BIGINT NOT NULL,
    total_minor_units BIGINT NOT NULL,
    currency_code TEXT NOT NULL DEFAULT 'GHS',
    state TEXT NOT NULL DEFAULT 'PENDING',
    idempotency_key TEXT NOT NULL,
    provider_transaction_reference TEXT NOT NULL,
    provider_reference TEXT,
    operator_id UUID NOT NULL REFERENCES operators (id),
    reason TEXT NOT NULL,
    failure_code TEXT,
    provider_requested_at TIMESTAMPTZ,
    succeeded_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT refunds_source_account_check
        CHECK (source_account IN ('GOAL_POOL', 'SUSPENSE')),
    CONSTRAINT refunds_amount_positive_check CHECK (amount_minor_units > 0),
    CONSTRAINT refunds_platform_fee_check CHECK (platform_fee_minor_units >= 0),
    CONSTRAINT refunds_processing_fee_check CHECK (processing_fee_minor_units >= 0),
    CONSTRAINT refunds_total_breakdown_check
        CHECK (total_minor_units =
            amount_minor_units + platform_fee_minor_units + processing_fee_minor_units),
    CONSTRAINT refunds_currency_check CHECK (currency_code = 'GHS'),
    CONSTRAINT refunds_state_check
        CHECK (state IN ('PENDING', 'PROVIDER_PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT refunds_reason_check CHECK (length(btrim(reason)) > 0),
    CONSTRAINT refunds_contribution_idempotency_key
        UNIQUE (contribution_id, idempotency_key),
    CONSTRAINT refunds_provider_reference_key UNIQUE (provider_reference)
);

CREATE UNIQUE INDEX refunds_one_active_per_contribution
    ON refunds (contribution_id)
    WHERE state IN ('PENDING', 'PROVIDER_PENDING', 'SUCCEEDED');

CREATE INDEX idx_refunds_goal_state ON refunds (goal_id, state);
CREATE INDEX idx_refunds_state_updated_at ON refunds (state, updated_at);

ALTER TABLE ledger_entries
    ADD COLUMN refund_id UUID REFERENCES refunds (id);

CREATE INDEX idx_ledger_entries_refund_id ON ledger_entries (refund_id);

CREATE TABLE refund_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    refund_id UUID NOT NULL REFERENCES refunds (id),
    event TEXT NOT NULL,
    from_state TEXT,
    to_state TEXT,
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refund_events_refund_id ON refund_events (refund_id, created_at);

CREATE TRIGGER refund_events_append_only
BEFORE UPDATE OR DELETE ON refund_events
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER refund_events_no_truncate
BEFORE TRUNCATE ON refund_events
FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_truncate();
