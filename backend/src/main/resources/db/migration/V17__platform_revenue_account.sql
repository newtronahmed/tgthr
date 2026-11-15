-- ADR 0019: a dedicated platform-revenue account for miscellaneous income from unresolved suspense.
-- Only the account check changes; there is no new ledger entry type and no data rewrite.

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
            'PLATFORM_LOSS',
            'PLATFORM_REVENUE'
        ));
