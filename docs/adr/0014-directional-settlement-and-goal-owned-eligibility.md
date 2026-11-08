# ADR 0014: Directional settlement and goal-owned payout eligibility

- Status: Accepted
- Date: 2026-11-08
- Deciders: Ahmed Zubairu
- Constitution articles touched: I (money is exact), IV (idempotent/consistent), V (trust)

## Context

ADR 0012 introduced settlement batches but defined payout eligibility as the sum of settlement net
amounts. Paystack's net contains the platform fee, and reversals/chargebacks have no sign. That would
let a goal pay out the platform's fee and could inflate eligibility with suspended late payments.
Settlement batches also need a directional representation for chargebacks/reversals and a
status-aware uniqueness rule to permit retry after a failed batch.

## Decision

Settlement batches and items are directional:

- `settlements.direction`: `CREDIT` or `DEBIT`; gross/fees/net remain positive absolute amounts.
- `settlement_items.direction`: `CREDIT` or `DEBIT`; amounts remain positive.
- `settlement_items.goal_amount_minor_units`: the positive goal-owned portion of the line. A
  contribution item uses the contribution amount A (not the settlement net); non-goal adjustments
  use zero.
- Only `PENDING` batches accept items. `PENDING → SETTLED | FAILED`; items are append-only.
- A contribution may have one `CONTRIBUTION` item in a non-`FAILED` batch. A `FAILED` batch can be
  retried. Duplicate settlement raises an alert and is rejected.
- `markSettled` requires signed item gross/fees/net to equal the batch totals; otherwise it raises an
  alert and refuses the transition.

Goal-owned settled eligibility:

```
settledGoal = Σ (item.direction = CREDIT ? goal_amount : -goal_amount)
              over items in SETTLED batches, for contributions where
              requires_reconciliation = false
availableForPayout = min(goalPoolLedgerBalance, max(0, settledGoal))
```

The platform fee and processing fee are excluded. Suspense contributions are excluded until an
operator reclassifies them. Prior payouts already reduce the goal pool ledger balance; they are not
subtracted twice. This supersedes ADR 0012's eligibility wording and item model; ADR 0012 remains
the batch/settlement-tracking decision.

## Consequences

**Good**

- Goals cannot pay out the platform fee or suspended late payments.
- Reversals and chargebacks reduce settled eligibility with an explicit direction.
- Failed-batch retry and append-only history are enforceable.

**Costs and risks**

- Every settlement line must carry a direction and a goal-owned amount.
- Mixed-direction batches require signed total reconciliation.
- The payout service (FEAT-002) must compute and test the `min(...)` eligibility rule.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Sum settlement net | Includes platform fees and suspense; overpays goals |
| Defer reversals/chargebacks | Leaves the new settlement schema unable to represent real provider reports |
| Signed `Money` amounts | Money remains non-negative; direction is explicit and auditable |
