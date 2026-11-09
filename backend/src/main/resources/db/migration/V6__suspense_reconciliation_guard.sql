-- INV-LEDGER-008: a SUSPENSE_HOLD credit may only exist for a contribution explicitly flagged
-- for reconciliation. This keeps the ledger and the operator flag from diverging.
CREATE FUNCTION assert_suspense_reconciliation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.account = 'SUSPENSE' AND NEW.entry_type = 'SUSPENSE_HOLD' THEN
        IF NEW.contribution_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM contributions c
            WHERE c.id = NEW.contribution_id AND c.requires_reconciliation = true
        ) THEN
            RAISE EXCEPTION 'SUSPENSE_HOLD requires a reconciliation-flagged contribution'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_suspense_guard
BEFORE INSERT ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION assert_suspense_reconciliation();
