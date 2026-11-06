-- FEAT-001 ledger: immutable double-entry entries. Balances are derived from these rows
-- (INV-LEDGER-006); nothing here stores a balance. Append-only is enforced by triggers.

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    posting_set_id UUID NOT NULL,
    contribution_id UUID REFERENCES contributions (id),
    account TEXT NOT NULL,
    entry_type TEXT NOT NULL,
    direction TEXT NOT NULL,
    amount_pesewas BIGINT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'GHS',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_entries_amount_positive_check CHECK (amount_pesewas > 0),
    CONSTRAINT ledger_entries_currency_check CHECK (currency = 'GHS'),
    CONSTRAINT ledger_entries_account_check
        CHECK (account IN
            ('PARTNER_HELD', 'GOAL_POOL', 'FEE', 'PLATFORM_FEE', 'BANK', 'WELFARE_RESERVE')),
    CONSTRAINT ledger_entries_type_check
        CHECK (entry_type IN
            ('CONTRIBUTION', 'FEE', 'PLATFORM_FEE', 'PAYOUT', 'PAYOUT_FEE', 'REVERSAL',
             'ADJUSTMENT', 'RESERVE_ROLLOVER')),
    CONSTRAINT ledger_entries_direction_check CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_posting_set_key
        UNIQUE (posting_set_id, entry_type, account, direction)
);

CREATE INDEX idx_ledger_entries_posting_set_id ON ledger_entries (posting_set_id);
CREATE INDEX idx_ledger_entries_contribution_id ON ledger_entries (contribution_id);

CREATE FUNCTION assert_posting_set_balanced() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    net BIGINT;
BEGIN
    SELECT coalesce(
               sum(CASE WHEN direction = 'DEBIT' THEN amount_pesewas ELSE -amount_pesewas END), 0)
    INTO net
    FROM ledger_entries
    WHERE posting_set_id = NEW.posting_set_id;

    IF net <> 0 THEN
        RAISE EXCEPTION 'posting set % does not net to zero (net % pesewas)',
            NEW.posting_set_id, net
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_entries_balanced
AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION assert_posting_set_balanced();

CREATE FUNCTION reject_append_only_mutation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; add a correcting entry instead', TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
    RETURN NULL;
END;
$$;

CREATE TRIGGER ledger_entries_append_only
BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER contribution_events_append_only
BEFORE UPDATE OR DELETE ON contribution_events
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
