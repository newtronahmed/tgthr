-- T500: persist the deterministic client refund reference sent to Paystack. It is derived from the
-- refund id, so the backfill reproduces the same value the adapter sends; storing it keeps an
-- already-submitted refund reconcilable even if the derivation ever changes.

ALTER TABLE refunds
    ADD COLUMN client_reference TEXT;

UPDATE refunds
SET client_reference = 'tgthr-refund-' || id
WHERE client_reference IS NULL;

ALTER TABLE refunds
    ALTER COLUMN client_reference SET NOT NULL,
    ADD CONSTRAINT refunds_client_reference_key UNIQUE (client_reference);
