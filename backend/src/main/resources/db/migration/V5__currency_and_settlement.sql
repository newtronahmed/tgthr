-- V5: Currency-aware Money (ADR 0011) and settlement tracking (ADR 0012)

-- Add currency to goals
ALTER TABLE goals
    ADD COLUMN currency_code TEXT NOT NULL DEFAULT 'GHS';

ALTER TABLE goals
    ADD CONSTRAINT goals_currency_code_check
        CHECK (currency_code ~ '^[A-Z]{3}$');

-- Add currency to contributions
ALTER TABLE contributions
    ADD COLUMN currency_code TEXT NOT NULL DEFAULT 'GHS';

ALTER TABLE contributions
    ADD CONSTRAINT contributions_currency_code_check
        CHECK (currency_code ~ '^[A-Z]{3}$');

-- Reconciliation flag for late payments after goal closure (ADR 0013)
ALTER TABLE contributions
    ADD COLUMN requires_reconciliation BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_contributions_requires_reconciliation
    ON contributions (requires_reconciliation)
    WHERE requires_reconciliation = TRUE;

-- Settlement batches (ADR 0012)
CREATE TABLE settlements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider TEXT NOT NULL,
    provider_settlement_reference TEXT NOT NULL,
    settlement_date DATE NOT NULL,
    gross_amount_minor_units BIGINT NOT NULL,
    currency_code TEXT NOT NULL,
    fees_minor_units BIGINT NOT NULL,
    net_amount_minor_units BIGINT NOT NULL,
    direction TEXT NOT NULL DEFAULT 'CREDIT',
    status TEXT NOT NULL DEFAULT 'PENDING',
    received_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT settlements_provider_check
        CHECK (provider IN ('PAYSTACK')),
    CONSTRAINT settlements_currency_code_check
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT settlements_gross_positive_check
        CHECK (gross_amount_minor_units > 0),
    CONSTRAINT settlements_fees_check
        CHECK (fees_minor_units >= 0),
    CONSTRAINT settlements_net_positive_check
        CHECK (net_amount_minor_units > 0),
    CONSTRAINT settlements_net_breakdown_check
        CHECK (net_amount_minor_units = gross_amount_minor_units - fees_minor_units),
    CONSTRAINT settlements_direction_check
        CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT settlements_status_check
        CHECK (status IN ('PENDING', 'SETTLED', 'FAILED')),
    CONSTRAINT settlements_received_check
        CHECK (status <> 'SETTLED' OR received_at IS NOT NULL),
    CONSTRAINT settlements_provider_reference_key
        UNIQUE (provider, provider_settlement_reference)
);

-- Settlement items (ADR 0012)
CREATE TABLE settlement_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_id UUID NOT NULL REFERENCES settlements (id),
    contribution_id UUID REFERENCES contributions (id),
    item_type TEXT NOT NULL,
    direction TEXT NOT NULL DEFAULT 'CREDIT',
    gross_minor_units BIGINT NOT NULL,
    currency_code TEXT NOT NULL,
    fee_minor_units BIGINT NOT NULL,
    net_minor_units BIGINT NOT NULL,
    goal_amount_minor_units BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT settlement_items_item_type_check
        CHECK (item_type IN ('CONTRIBUTION', 'ADJUSTMENT', 'REVERSAL', 'CHARGEBACK')),
    CONSTRAINT settlement_items_direction_check
        CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT settlement_items_goal_amount_check
        CHECK (goal_amount_minor_units >= 0 AND goal_amount_minor_units <= gross_minor_units),
    CONSTRAINT settlement_items_currency_code_check
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT settlement_items_gross_positive_check
        CHECK (gross_minor_units > 0),
    CONSTRAINT settlement_items_fee_check
        CHECK (fee_minor_units >= 0),
    CONSTRAINT settlement_items_net_positive_check
        CHECK (net_minor_units > 0),
    CONSTRAINT settlement_items_net_breakdown_check
        CHECK (net_minor_units = gross_minor_units - fee_minor_units)
);

CREATE INDEX idx_settlement_items_settlement_id
    ON settlement_items (settlement_id);

CREATE INDEX idx_settlement_items_contribution_id
    ON settlement_items (contribution_id)
    WHERE contribution_id IS NOT NULL;

-- Ledger currency (ADR 0011). V2 already has a GHS-only `currency` column; rename it instead
-- of adding a second currency column, and relax the check to ISO-4217 shape.
ALTER TABLE ledger_entries
    DROP CONSTRAINT ledger_entries_currency_check;

ALTER TABLE ledger_entries
    RENAME COLUMN currency TO currency_code;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_currency_code_check
        CHECK (currency_code ~ '^[A-Z]{3}$');

-- New ledger accounts for suspense and loss allocation (ADR 0013)
ALTER TABLE ledger_entries
    DROP CONSTRAINT IF EXISTS ledger_entries_type_check;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_type_check
        CHECK (entry_type IN (
            'CONTRIBUTION',
            'FEE',
            'PLATFORM_FEE',
            'PAYOUT',
            'PAYOUT_FEE',
            'REVERSAL',
            'ADJUSTMENT',
            'RESERVE_ROLLOVER',
            'SUSPENSE_HOLD',
            'LOSS_ALLOCATION'
        ));

ALTER TABLE ledger_entries
    DROP CONSTRAINT IF EXISTS ledger_entries_account_check;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_account_check
        CHECK (account IN (
            'GOAL_POOL',
            'PARTNER_HELD',
            'FEE',
            'PLATFORM_FEE',
            'PAYOUT_FEE',
            'BANK',
            'WELFARE_RESERVE',
            'SUSPENSE',
            'PLATFORM_LOSS'
        ));

-- A posting set must not mix currencies; the balance trigger now checks both zero-sum and currency.
CREATE OR REPLACE FUNCTION assert_posting_set_balanced() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    net BIGINT;
    currencies INTEGER;
BEGIN
    SELECT count(DISTINCT currency_code),
           coalesce(sum(CASE WHEN direction = 'DEBIT' THEN amount_pesewas
                             ELSE -amount_pesewas END), 0)
    INTO currencies, net
    FROM ledger_entries
    WHERE posting_set_id = NEW.posting_set_id;

    IF currencies > 1 THEN
        RAISE EXCEPTION 'posting set % mixes currencies', NEW.posting_set_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF net <> 0 THEN
        RAISE EXCEPTION 'posting set % does not net to zero (net % pesewas)',
            NEW.posting_set_id, net
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

-- Settlement lifecycle and item guards (ADR 0014).
CREATE FUNCTION guard_settlement_status() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.status <> 'PENDING' THEN
        RAISE EXCEPTION 'settlement must be created PENDING'
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.status <> 'PENDING' THEN
            RAISE EXCEPTION 'settlement is terminal'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.status NOT IN ('PENDING', 'SETTLED', 'FAILED') THEN
            RAISE EXCEPTION 'invalid settlement status'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER settlements_status_guard
BEFORE INSERT OR UPDATE ON settlements
FOR EACH ROW EXECUTE FUNCTION guard_settlement_status();

CREATE FUNCTION guard_settlement_item() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    parent_status TEXT;
BEGIN
    SELECT status INTO parent_status FROM settlements WHERE id = NEW.settlement_id;
    IF parent_status IS NULL OR parent_status <> 'PENDING' THEN
        RAISE EXCEPTION 'settlement batch % is not pending', NEW.settlement_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.item_type = 'CONTRIBUTION' THEN
        IF NEW.contribution_id IS NULL THEN
            RAISE EXCEPTION 'a CONTRIBUTION item requires a contribution'
                USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (
            SELECT 1
            FROM settlement_items i
            JOIN settlements s ON s.id = i.settlement_id
            WHERE i.contribution_id = NEW.contribution_id
              AND i.item_type = 'CONTRIBUTION'
              AND s.status <> 'FAILED'
        ) THEN
            RAISE EXCEPTION 'contribution % already has a non-failed settlement', NEW.contribution_id
                USING ERRCODE = 'unique_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER settlement_items_guard
BEFORE INSERT ON settlement_items
FOR EACH ROW EXECUTE FUNCTION guard_settlement_item();

CREATE TRIGGER settlement_items_append_only
BEFORE UPDATE OR DELETE ON settlement_items
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER settlement_items_no_truncate
BEFORE TRUNCATE ON settlement_items
FOR EACH STATEMENT EXECUTE FUNCTION reject_append_only_truncate();

COMMENT ON TABLE settlements IS 'Settlement batches from payment provider (ADR 0012)';
COMMENT ON TABLE settlement_items IS 'Individual items in a settlement batch (ADR 0012)';
COMMENT ON COLUMN contributions.requires_reconciliation IS 'True for late payments after goal closure; parked in SUSPENSE until operator resolves (ADR 0013)';
COMMENT ON COLUMN contributions.currency_code IS 'ISO 4217 currency code; GHS only for MVP (ADR 0011)';
