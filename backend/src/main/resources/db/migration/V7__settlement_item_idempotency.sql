-- T290: provider settlement lines are idempotent within a batch; CSV retries cannot duplicate
-- adjustments. A null reference is allowed for manually created lines.
ALTER TABLE settlement_items
    ADD COLUMN provider_item_reference TEXT;

CREATE UNIQUE INDEX settlement_items_provider_item_reference
    ON settlement_items (settlement_id, provider_item_reference)
    WHERE provider_item_reference IS NOT NULL;
