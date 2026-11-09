-- ADR 0011 implementation: rename legacy *_pesewas columns to *_minor_units now.
-- V7 must be applied first so the migration order remains V1..V8.

ALTER TABLE goals
    RENAME COLUMN fixed_amount_pesewas TO fixed_amount_minor_units;
ALTER TABLE goals
    RENAME COLUMN target_amount_pesewas TO target_amount_minor_units;
ALTER TABLE goals
    RENAME COLUMN minimum_amount_pesewas TO minimum_amount_minor_units;
ALTER TABLE goals
    RENAME COLUMN second_approver_threshold_pesewas TO second_approver_threshold_minor_units;

ALTER TABLE contributions
    RENAME COLUMN amount_pesewas TO amount_minor_units;
ALTER TABLE contributions
    RENAME COLUMN processing_fee_pesewas TO processing_fee_minor_units;
ALTER TABLE contributions
    RENAME COLUMN platform_fee_pesewas TO platform_fee_minor_units;
ALTER TABLE contributions
    RENAME COLUMN total_pesewas TO total_minor_units;

ALTER TABLE ledger_entries
    RENAME COLUMN amount_pesewas TO amount_minor_units;

-- The V5 balance/currency trigger function is bound to the old column name at execution time.
CREATE OR REPLACE FUNCTION assert_posting_set_balanced() RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    net BIGINT;
    currencies INTEGER;
BEGIN
    SELECT count(DISTINCT currency_code),
           coalesce(sum(CASE WHEN direction = 'DEBIT' THEN amount_minor_units
                             ELSE -amount_minor_units END), 0)
    INTO currencies, net
    FROM ledger_entries
    WHERE posting_set_id = NEW.posting_set_id;

    IF currencies > 1 THEN
        RAISE EXCEPTION 'posting set % mixes currencies', NEW.posting_set_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF net <> 0 THEN
        RAISE EXCEPTION 'posting set % does not net to zero (net % minor units)',
            NEW.posting_set_id, net
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
