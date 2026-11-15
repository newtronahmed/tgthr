-- Bounded automatic re-verification for refunds whose provider outcome is unknown. The reservation is
-- released only by a definitive provider result; exhaustion escalates and stops automatic attempts.

ALTER TABLE refunds
    ADD COLUMN verification_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_verification_at TIMESTAMPTZ,
    ADD COLUMN verification_exhausted_at TIMESTAMPTZ,
    ADD CONSTRAINT refunds_verification_attempts_check CHECK (verification_attempts >= 0);

CREATE INDEX idx_refunds_due_verification
    ON refunds (next_verification_at)
    WHERE state = 'PROVIDER_PENDING' AND verification_exhausted_at IS NULL;
