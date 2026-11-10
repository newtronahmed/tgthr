-- Link payout ledger postings to the payout so the goal-pool balance can include completed
-- payouts. Contribution postings keep contribution_id; payout postings set payout_id.
ALTER TABLE ledger_entries
    ADD COLUMN payout_id UUID REFERENCES payouts (id);

CREATE INDEX idx_ledger_entries_payout_id
    ON ledger_entries (payout_id)
    WHERE payout_id IS NOT NULL;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_entries_payout_link_check
        CHECK (
            (entry_type IN ('PAYOUT', 'PAYOUT_FEE') AND payout_id IS NOT NULL)
            OR (entry_type NOT IN ('PAYOUT', 'PAYOUT_FEE') AND payout_id IS NULL)
        );

CREATE UNIQUE INDEX ledger_entries_payout_line
    ON ledger_entries (payout_id, entry_type, account, direction)
    WHERE payout_id IS NOT NULL;
