# ADR 0020: Refund verification must outlast the provider's refund window

- Status: Proposed
- Date: 2026-11-27
- Deciders: repository owner (CODEOWNER `ledger/`, `payments/`)
- Constitution articles touched: Article I.5 (money must not be silently dropped), Article II.4
  (configuration changes that affect money need an amendment), Article IX.2 (anything stuck raises an
  alert)

## Context

`RefundVerificationJob` re-checks a `PROVIDER_PENDING` refund on a bounded exponential backoff. When
the attempts run out it marks the refund `verification_exhausted_at`, publishes one
`REFUND_VERIFICATION_EXHAUSTED` alert and stops. Exhaustion deliberately **keeps** the `GOAL_POOL`
reservation, because an unknown outcome is not proof that Paystack did not refund.

The shipped defaults do not outlast the provider. Measured from the code in
`RefundVerificationJob.backoff`, with `initial-delay=PT5M`, `max-delay=PT6H` and `max-attempts=8`:

| Attempt | At |
| --- | --- |
| 1 | +5m |
| 2 | +10m |
| 3 | +20m |
| 4 | +40m |
| 5 | +80m |
| 6 | +160m |
| 7 | +320m |
| 8 | +640m (**10.67h**) → exhausted |

The T401 sandbox spike recorded `expected_at` **11 days** out for a real refund, and both spike
refunds were still `pending` with no `refunded_at` minutes after creation
(`docs/runbooks/refund-operations.md` section 9). Paystack's refund settlement window is therefore
~25× longer than the automatic verification window.

Consequences today, for every genuine refund:

1. Automatic verification stops at 10.67h while the refund is still legitimately in flight.
2. One `REFUND_VERIFICATION_EXHAUSTED` alert fires for a refund that is behaving normally. An alert
   that always fires is noise: on-call learns to ignore it, which defeats Article IX.2.
3. The reservation stays held until an operator manually verifies, so goal availability is wrong for
   up to 11 days with no automatic resolution.
4. Operators are pushed toward manual verification of refunds that need no intervention.

Spec rule 10 makes the refund provider timeout configuration and requires an amendment to change a
timeout, so this is a spec-first decision rather than a constant to tweak.

## Decision

We will size the default automatic verification window to outlast the provider's refund settlement
window, with a safety margin, and treat exhaustion as a genuine exception rather than the expected
end state.

New defaults: `initial-delay=PT15M`, `max-delay=PT12H`, `max-attempts=24`, giving a **9.17-day**
window (83% of the recorded 11 days). The wait doubles 15m→30m→1h→2h→4h→8h and then holds at the
12h ceiling from attempt 7 onwards (17 capped waits), so a refund that has not settled near the end
of the window still gets a final check.

We will not raise the ceiling to cover the full 11 days without operator evidence. At `max-delay=PT12H`
and 24 attempts the window is 9.17d; covering all 11 days needs 28 attempts (11.17d). Instead the
trade-off is explicit: the automatic window covers the observed settlement window, and a refund still
unresolved at exhaustion is a real signal worth a human.

We will also make the exhausted state recoverable: an operator verify that returns a pending result
re-arms the backoff rather than leaving the refund permanently unverified.

## Consequences

**Good**

- The alert now means what it says. A refund that exhausts is genuinely exceptional — provider
  behaviour outside the recorded window — so the alert is worth acting on.
- Reservations resolve automatically for refunds that settle inside the observed window, without an
  operator touching goal availability.
- Fewer verification calls in the first hours (15m rather than 5m) and a much lower call rate
  overall, because the tail is 12h rather than doubling from 5m.

**Costs and risks**

- A refund whose outcome becomes known at, say, 2 days is resolved up to 12h later than it would be
  with a tighter schedule. The reservation is held in the meantime, which is the safe direction: the
  alternative is releasing a reservation for a refund that may still land.
- The 11-day figure comes from **two** sandbox refunds, not a distribution. A provider window that
  varies by payment method or by month could exceed the chosen window. Contained by keeping exhaustion
  as an alert and by the operator re-arm path, and by recording the observed `expected_at` in the
  runbook so the window can be re-derived.
- A malformed refund can now consume up to 24 verification calls instead of 8. Each is one HTTP GET
  on a small batch, bounded by `batch-size`; the rate is dominated by the 12h tail, not the count.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Keep 8 attempts and only raise `max-delay` | The early doubling dominates: with a 24h ceiling and 8 attempts the window is still 0.44d (11h), because attempts 1–4 are the 5/10/20/40m doubling. It needs ~10 attempts just to reach 1.8 days, and the early schedule stays too aggressive for a multi-day process. |
| Unbounded verification until the provider answers | Violates the bounded-retry intent of AC-REFUND-011 and leaves a refund checked forever with no escalation path. |
| Mark exhausted refunds `FAILED` and release the reservation | Explicitly rejected: an unknown outcome is not proof of no refund. Releasing could pay the goal pool and the contributor, which is the double-pay this feature exists to prevent. |
| Poll every 12h for the full 11 days, no backoff | 22 calls regardless of how fast the provider settles, and no early resolution for the common case that settles in hours. |
| Leave the defaults and rely on manual verification | What happens today; it is the defect this ADR addresses. |
