# ADR 0013: Refund and loss allocation policy

- Status: Accepted
- Date: 2026-11-08
- Deciders: Ahmed Zubairu
- Constitution articles touched: I (money is exact), V (trust is a feature), IX (observable)

## Context

Contributors may request refunds after payment (error, dispute, fraud). If the refund is processed **before payout**, the goal pool balance simply decreases. If processed **after payout**, the beneficiary has already received the money, and clawing it back may be legally or practically impossible.

A negative pool balance violates `INV-LEDGER-003` (pool balance never negative). The platform must explicitly allocate any unrecoverable loss rather than silently allowing negative balances or dropping the refund.

## Decision

### Timing-based refund policy

1. **Before payout:** Refunds are processed normally. A `REVERSAL` ledger entry debits the goal pool, reducing available payout balance. The contribution state becomes `REFUNDED`.
2. **After payout:** Refunds enter a **manual recovery workflow**. The platform attempts beneficiary recovery where contractually and legally permitted, but does not assume recovery is guaranteed.

### Loss allocation

When a post-payout refund or provider chargeback creates a shortfall that cannot be recovered from the beneficiary:

- The loss is allocated to the **responsible party** under the partner agreement:
  - If provider error or fraud: provider compensates or platform invoices them.
  - If platform error: platform absorbs the loss.
  - If contributor fraud (chargeback abuse): platform may pursue the contributor; unrecovered amounts are platform loss.
- The ledger records the loss explicitly via a `PLATFORM_LOSS` ledger account (new account type, credit to close the imbalance).
- Every loss allocation requires **operator approval** with a recorded reason; no automatic loss posting.

### Reconciliation and suspense

A contribution confirmed **after goal closure** (late success, per Decision #16) is flagged `requires_reconciliation` and its amount is credited to a `SUSPENSE` ledger account, not the goal pool. An operator investigates and either:
- Issues a refund to the contributor (debit `SUSPENSE`, normal refund flow).
- Reclassifies it to the goal pool or a related goal (debit `SUSPENSE`, credit goal pool, with approval audit trail).
- Records it as miscellaneous income if unclaimed (debit `SUSPENSE`, credit platform revenue, after a timeout period).

## Consequences

**Good**

- Pool balance never goes negative; shortfalls are explicit and auditable.
- Platform does not silently eat losses or assume clawback always succeeds.
- Late payments after closure are recorded (no money lost) but do not automatically inflate payout balance.
- Operator has tooling and audit trail for every exception.

**Costs and risks**

- Post-payout refund flow requires operator tooling (FEAT-005 or later): recovery request UI, approval workflow, loss-allocation posting.
- Beneficiary recovery may involve legal or contractual enforcement; platform needs terms that permit clawback.
- Suspense account creates a "parked money" category; must be monitored to prevent unbounded growth.
- Operator burden: each late payment or post-payout refund needs human judgment, not automatic resolution.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Block refunds after payout | Punishes legitimate contributor errors; bad UX and may violate Ghana consumer protection rules |
| Silently allow negative pool balance | Violates constitution; hides losses; makes reconciliation and reporting incoherent |
| Automatically claw back from beneficiary | Assumes clawback always succeeds; ignores legal/contractual constraints; creates silent failures |
| Platform always eats post-payout losses | Unsustainable at scale; correct allocation depends on fault (provider vs. platform vs. fraud) |
