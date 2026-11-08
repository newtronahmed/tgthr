# ADR 0012: Settlement tracking architecture

- Status: Accepted
- Date: 2026-11-08
- Deciders: Ahmed Zubairu
- Constitution articles touched: I (money is exact and traceable), IX (observable)

## Context

Paystack confirms a contribution immediately but settles net funds on the next working day (ADR 0006). A confirmed contribution represents a **receivable**, not cash in hand. If an organizer requests payout before settlement completes, we have no funds to transfer. The current ledger tracks obligations (`PARTNER_HELD` account) but not settlement status or timing.

Reconciliation requires matching our confirmed contributions against Paystack's settlement reports. Without local settlement tracking, this is a manual spreadsheet task prone to error.

## Decision

We will introduce **settlement batches** and **settlement items** to track provider settlement lifecycle.

**Schema:**

- `settlements` table: `id`, `provider` (enum: PAYSTACK), `provider_settlement_reference` (their batch ID), `settlement_date`, `gross_amount_minor_units`, `currency_code`, `fees_minor_units`, `net_amount_minor_units`, `status` (PENDING, SETTLED, FAILED), `received_at` (when we ingested their report), `created_at`, `updated_at`.
- `settlement_items` table: `id`, `settlement_id` (FK), `contribution_id` (FK, nullable for non-contribution items like adjustments), `item_type` (CONTRIBUTION, ADJUSTMENT, REVERSAL), `gross_minor_units`, `currency_code`, `fee_minor_units`, `net_minor_units`, `created_at`.
- Index on `contributions.id` and `settlement_items.contribution_id` for reconciliation queries.

**Payout eligibility:** The `payout` module (FEAT-002) checks that requested payout amount does not exceed the **settled net balance** (sum of `SETTLED` settlement items net amounts minus prior payouts). Contributions in `PENDING` settlements are not yet eligible for payout.

**Ingestion:** A settlement ingestion job (manual or automated) reads Paystack settlement reports, creates a `settlement` batch record, links contributions via `provider_reference`, and marks the settlement `SETTLED`. Unlinked items (provider adjustments, chargebacks) create `settlement_items` with `contribution_id = NULL` and raise alerts.

**Ledger postings:** Settlement does **not** post ledger entries immediately. The ledger tracks obligations; settlement tracks cash flow. A future ADR may add `SETTLEMENT` ledger entry type to record cash receipt from partner, but that is deferred until reconciliation pain justifies it.

## Consequences

**Good**

- Payout eligibility is based on actual settled funds, not just confirmed obligations.
- Automated reconciliation: compare our `CONFIRMED` contributions against Paystack settlement item list; mismatches raise alerts.
- Operator visibility: settlement dashboard shows pending vs. settled amounts, settlement lag, unmatched items.

**Costs and risks**

- Settlement ingestion requires building a report parser (CSV or API) for Paystack's format; manual upload is the MVP fallback.
- Settlement lag (T+1) delays payouts; organizers see "funds pending settlement" instead of immediate availability. Mitigate with clear messaging and estimated settlement dates.
- Chargebacks or provider adjustments create settlement items with no matching contribution; must be investigated and either matched (late reconciliation) or recorded as adjustments.

## Alternatives considered

| Option | Why not |
| --- | --- |
| No settlement tracking; payout checks Paystack balance API | Real-time balance check on every payout adds latency and external dependency; cannot reconcile historical mismatches |
| Add `settlement_status` to contributions table | Does not model batch settlements or non-contribution adjustments; loses settlement timing and gross/net breakdown |
| Poll Paystack balance and assume sufficiency | Ignores settlement lag, chargebacks, and reconciliation; silent failures when balance is insufficient |
| Model settlement as ledger entries | Ledger tracks obligations, settlement tracks cash; conflating them makes both harder to reason about |
