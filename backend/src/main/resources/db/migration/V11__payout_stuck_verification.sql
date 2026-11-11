-- One verification flag per stuck payout so a multi-instance sweep alerts once.
CREATE UNIQUE INDEX payout_events_verification_required_once
    ON payout_events (payout_id)
    WHERE event = 'VERIFICATION_REQUIRED';

-- Serves the stale-EXECUTING sweep without scanning every payout.
CREATE INDEX idx_payouts_executing_at
    ON payouts (executing_at)
    WHERE state = 'EXECUTING';
