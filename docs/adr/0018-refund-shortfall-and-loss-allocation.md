# ADR 0018: Refund shortfall and loss-allocation shape

- Status: Accepted
- Date: 2026-11-14
- Deciders: Ahmed Zubairu
- Constitution articles touched: I (money is exact), IV (idempotent and consistent), V (trust is a feature)
- Supersedes: ADR 0013 in part (the retained-refund-fee `LOSS_ALLOCATION` shape)

## Context

ADR 0013 accepts `LOSS_ALLOCATION` as a debit to `PLATFORM_LOSS` and a credit to the goal pool
(to clear a negative pool) or to partner held (when the partner compensates). FEAT-005 adds a
different event: a full pre-payout refund of `A + P + F` where the payment partner keeps its
processing fee `F`.

The full refund reverses the confirmation posting: debit `GOAL_POOL` or `SUSPENSE` `A`,
`PLATFORM_FEE` `P`, `FEE` `F`; credit `PARTNER_HELD` `A + P + F`. If the partner keeps `F`, the
contributor is still paid `A + P + F`, so the platform must fund the difference.

The ledger currently records obligations only. Settlement does not post (ADR 0012), and no current
code posts to `BANK`; a `BANK` credit would leave that account negative and misstate cash. The
shortfall is owed to the partner, so it belongs on `PARTNER_HELD`.

## Decision

When a full pre-payout refund succeeds and the partner retains the processing fee `F`, post the
`REVERSAL` and this `LOSS_ALLOCATION` set in the **same transaction**:

| Entry | Debit | Credit |
| --- | --- | --- |
| `REVERSAL` | `GOAL_POOL` (or `SUSPENSE`) `A`; `PLATFORM_FEE` `P`; `FEE` `F` | `PARTNER_HELD` `A + P + F` |
| `LOSS_ALLOCATION` | `PLATFORM_LOSS` `F` | `PARTNER_HELD` `F` |

- The `PARTNER_HELD` credit balance of `F` is intentional: it records that the platform owes the
  partner the fee the partner retained. The platform funds the refund through the partner so the
  contributor receives the gross amount, and the obligation remains on `PARTNER_HELD` until a
  settlement-posting ADR clears it. It is not a negative cash balance.
- No `BANK` entry posts for this event.
- When the partner returns `F` with the refund, only the `REVERSAL` posts; no loss set and no
  `PARTNER_HELD` obligation remain.
- The two sets and the `CONFIRMED → REFUNDED` transition commit together. A crash cannot leave the
  contributor whole in the ledger while hiding the fee.
- This ADR supersedes only ADR 0013's `LOSS_ALLOCATION` shape for the retained-refund-fee event.
  ADR 0013's text stays unchanged, and its goal-pool/partner-held shapes continue to apply to
  post-payout recovery.

## Consequences

**Good**

- The contributor is made whole without a negative `BANK` balance or premature settlement posting.
- The platform's obligation to the partner is explicit, auditable and balanced.
- The reversal and loss allocation cannot diverge under a crash.

**Costs and risks**

- `PARTNER_HELD` can carry a credit balance for this event. Reports and reconciliation must treat it
  as an obligation, not as cash.
- A future settlement-posting ADR must clear this obligation when the fee is settled.
- A retained fee increases the platform loss; the refund reason and alert make it visible.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Credit `BANK` | The ledger does not post settlement cash and `BANK` has no balance; the credit would go negative. |
| Credit `GOAL_POOL` | The goal has already been debited `A`; crediting it would overstate the pool or leave a phantom balance. |
| Do not refund `F` | Violates the accepted full-refund policy and leaves the contributor short. |
| Post a partner top-up asset first | Adds a cash/asset posting that ADR 0012 explicitly defers. |
