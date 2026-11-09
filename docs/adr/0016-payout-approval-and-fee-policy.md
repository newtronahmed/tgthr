# ADR 0016: Payout approval, fee and execution policy

- Status: Accepted
- Date: 2026-11-09
- Deciders: Ahmed Zubairu
- Constitution articles touched: I (money is exact), IV (idempotent), V (trust), VIII (privacy), IX (observable)

## Context

FEAT-001/003 collect and lock funds; FEAT-002 must release them. The settlement model now distinguishes
settled cash from confirmed obligations (ADR 0014). The remaining product choices were: whether
payouts are partial, who the second approver is, who bears the provider transfer fee, and how freeze,
cancel and retry behave. Leaving these implicit would let a payout bypass the goal's safeguards or
silently deduct money.

## Decision

- **Partial payouts.** The organizer may request any amount up to `availableForPayout`, repeatedly
  over time. Each payout is an all-in goal-pool debit with its own idempotency key.
- **Designated-member approval.** When the goal's rule triggers, a named group member designated at
  goal creation (or before the first payout) approves by SMS OTP. The member needs no account. The
  approver phone is encrypted and distinct from the organizer; only the name is public. The approval
  token/code is hashed, single-use, expiring and attempt-limited.
- **Goal bears the fee.** The provider transfer fee is quoted before the request and deducted from
  the requested amount: the goal pool is debited X, the beneficiary receives X-G, and `PAYOUT_FEE`
  is credited G. A changed provider fee fails the payout rather than adjusting silently.
- **Hold, freeze, cancel, retry.** Approval precedes the hold; expiry makes the payout READY. A
  FROZEN goal blocks new requests and READY->EXECUTING; CLOSED allows payouts. Cancellation is
  allowed before EXECUTING only. Execution is idempotent; a definitive failure is terminal with no
  automatic retry; an unknown result remains EXECUTING for verification.
- **Ledger.** Only COMPLETED posts the balanced payout set; request, hold, cancel and failure post
  nothing.

## Consequences

**Good**

- Organizers can access settled funds without waiting for the whole goal; the goal cannot pay out
  unsettled or suspended money.
- A second, independent member controls the risky transfers; no contributor account is required.
- Fees, approvals and freezes are visible and auditable; the goal pool never goes negative.

**Costs and risks**

- The approver can be unavailable; the organizer can cancel, and a new request/approval can be
  sent. Operator intervention remains a later feature.
- The provider fee schedule and transfer latency must be verified in the sandbox; a stale quote can
  fail a payout.
- The approver phone is sensitive personal data and needs encryption, rate limits and delivery
  monitoring.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Full-balance payouts only | Forces all-or-nothing withdrawals and complicates later staged payouts |
| Operator-only approval | Stronger but adds an operator console and does not use the group's chosen person |
| Organizer self-approval + hold | A hijacked organizer can still move money; the second member is the safeguard |
| Platform absorbs the transfer fee | Sustainable only as a deliberate marketing decision |
| Auto-retry failed transfers | Can duplicate a transfer after a timeout; verification and a new idempotent request are safer |
