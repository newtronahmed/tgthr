-- FEAT-005 suspense slice: one resolution per suspense item. Reclassification and income post
-- immediately; an income proposal parks the item as PROPOSED until a distinct approver applies it.
-- The ledger remains the source of truth; the queue derives from the contribution flag and this
-- table.

CREATE TABLE reconciliation_resolutions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contribution_id UUID NOT NULL REFERENCES contributions (id),
    resolution TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'APPLIED',
    proposer_id UUID NOT NULL REFERENCES operators (id),
    approver_id UUID REFERENCES operators (id),
    reason TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    approval_idempotency_key TEXT,
    posting_set_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    approved_at TIMESTAMPTZ,
    CONSTRAINT reconciliation_resolutions_contribution_key UNIQUE (contribution_id),
    CONSTRAINT reconciliation_resolutions_resolution_check
        CHECK (resolution IN ('REFUND', 'RECLASSIFY', 'INCOME')),
    CONSTRAINT reconciliation_resolutions_status_check
        CHECK (status IN ('PROPOSED', 'APPLIED')),
    CONSTRAINT reconciliation_resolutions_reason_check CHECK (length(btrim(reason)) > 0),
    CONSTRAINT reconciliation_resolutions_income_approver_check
        CHECK (resolution <> 'INCOME' OR status <> 'APPLIED' OR approver_id IS NOT NULL)
);

CREATE INDEX idx_reconciliation_resolutions_status
    ON reconciliation_resolutions (status, created_at);
