# CODEOWNER review plan: payout posting and contributor notifications

- Date: 2026-11-13
- Feature: FEAT-002 Payouts
- Planning baseline: `20267a1` (record the actual reviewed commit in the T365 review)
- Prepared by: engineering
- Required sign-off: CODEOWNER for `ledger/` and `payments/` (Constitution I.4). This plan does not
  replace the review or the sign-off.

## Purpose

Give the CODEOWNER a bounded, evidence-backed review of the money and notification paths before
`PAYOUT_TRANSFER_PROVIDER=paystack` is enabled for real payouts. The review must confirm that a
completed payout posts exactly one balanced ledger set, that no other state posts, and that
contributor notifications are once-only, phone-free and failure-safe.

## Prerequisites

- [ ] `make check` green on the reviewed commit.
- [ ] `python3 tools/check_traceability.py` reports 91 defined / 91 active / 0 pending / 0 missing.
- [ ] All four CI checks green (Backend, Specs/API, Web build, Web E2E).
- [ ] Read the binding documents: `specs/constitution.md`, `specs/features/002-payouts/spec.md`,
  `specs/domain/payout-state-machine.md`, `specs/domain/ledger.md`, `specs/domain/settlement.md`.
- [ ] Read ADRs 0002 (append-only ledger), 0005 (payout safeguards), 0006 (Paystack), 0007 (fees),
  0012 (settlement), 0013 (refund/loss allocation), 0014 (goal-owned eligibility), 0016 (approval and
  fee policy).
- [ ] Read `docs/runbooks/payout-operations.md` and `docs/runbooks/paystack-transfer-spike.md`.
- [ ] Confirm the release candidate keeps `PAYOUT_ENABLED=false`,
  `PAYOUT_EXECUTION_ENABLED=false`, `PAYOUT_TRANSFER_PROVIDER=none` and `PAYOUT_FEE_PROVIDER=none`
  until this review and the live Paystack sign-off are complete.

## Scope

### Payout posting path

- `backend/src/main/java/com/groupcollections/payout/PayoutPosting.java`
- `PayoutCompletionService.java`
- `PayoutClaimService.java`
- `PayoutService.java` (request/cancel and goal lock)
- `goal/OrganizerGoalService.java` (payout-settings PATCH and goal lock)
- `PayoutExecutionService.java`
- `PayoutVerificationService.java`
- `PayoutRepository.java` (claim/CAS, provider reference, completed totals, reservations, stuck query)
- `PayoutStateMachine.java`
- `ledger/LedgerRepository.java` (`postPayout`), `LedgerAccount`, `LedgerEntryType`, `LedgerEntry`
- Migrations `V9__payout_approver_and_payouts.sql`, `V10__payout_ledger_link.sql`,
  `V11__payout_stuck_verification.sql`
- `PayoutExecutionIT`, `PayoutCancelIT`, `PayoutInvariantsTest`, `PayoutVerificationServiceTest`

### Contributor notification path

- `PayoutNotificationService.java`
- `PayoutCompletionService.java` (after-commit invocation)
- `payout_notifications` table and unique `(payout_id, payer_phone_hash)`
- `PayoutContributorIT`, `PayoutExecutionIT` notification assertions
- Public `PayoutPublicView`/report paths only where they expose payout or contributor data

### Adjacent paths in scope

- Paystack payout adapter amount/currency and duplicate-reference handling
  (`PaystackPayoutTransferProvider`, `PaystackPayoutFeeProvider`, `Network`)
- Fee re-check in `PayoutClaimService`
- Reservation release on cancel/failure/reject
- Phone-free events, metrics and alerts

## Money and state checklist

- [ ] Only `COMPLETED` posts ledger. `REQUESTED`, `APPROVED`, `HOLD`, `READY`, `EXECUTING`,
  `CANCELLED` and `FAILED` post nothing.
- [ ] A completed payout posts **one** posting set: debit `GOAL_POOL` X; credit `PARTNER_HELD` X−G;
  credit `PAYOUT_FEE` G iff G > 0. Lines balance, use integer minor units and GHS.
- [ ] The posting set is unique per payout and the ledger remains append-only; corrections are new
  entries.
- [ ] The provider transfer amount is the beneficiary net X−G, not X; the fee is the quoted G.
- [ ] The fee is re-quoted under the goal lock **before** the `READY → EXECUTING` compare-and-set,
  not after the claim. A mismatch marks the payout FAILED with `PAYOUT_FEE_MISMATCH`, posts nothing,
  calls no provider, releases the reservation and returns no claimed payout.
- [ ] `PayoutCompletionService.complete` inserts the ledger lines and then calls `markCompleted`; the
  state guard is the `where state = 'EXECUTING'` update. Confirm a failed guard throws in the same
  transaction and rolls the insert back. `LedgerRepository.postPayout` only inserts the lines it is
  given; it does not itself reject an unbalanced set, so the caller's posting is the balance proof.
- [ ] `GOAL_POOL` cannot go negative; `available` is capped by goal-owned settled funds minus
  completed/reserved amounts.
- [ ] Cancel/reject/failure release reservations and never post ledger.
- [ ] A repeat of the same `Idempotency-Key` with the same amount returns the original payout and
  posts nothing; a different amount is 409. There is no RETRY state-machine event.
- [ ] Stuck EXECUTING rows are never re-executed. Completion failure leaves EXECUTING, alerts and
  preserves the provider reference.
- [ ] Provider references are unique; duplicate-reference handling verifies the existing transfer and
  never mints a new reference.
- [ ] A success result is accepted only when the confirmed amount equals the net and the currency is
  GHS.

## Notification checklist

- [ ] Notifications are registered `afterCommit` only when a transaction synchronization is active.
  Name the `else` branch in `PayoutCompletionService.complete` that calls `notifyCompleted` inline
  while the goal row is locked; require proof production cannot hit it, or a task to remove it.
- [ ] One notification per contributor per payout, enforced by the unique
  `(payout_id, payer_phone_hash)` constraint; duplicate payer rows collapse.
- [ ] Only confirmed, non-reconciliation contributions fund the notification set.
- [ ] A failed SMS deletes the claim row, increments `payout.notification.failed`, publishes
  `SMS_FAILURE`, and leaves the payout COMPLETED.
- [ ] A retry inserts only missing rows and does not double-send.
- [ ] Payer phones are decrypted only for delivery; events, logs, metrics, alerts, public responses
  and notification rows contain no plaintext phone.
- [ ] The notification message contains no approval link/code or sensitive reference.

## Concurrency and locking checklist

- [ ] The goal row lock is taken by `PayoutService.request`, `OrganizerGoalService.updatePayoutSettings`,
  `PayoutClaimService.claimNext`, `PayoutCompletionService.complete` and `PayoutService.cancel`.
  `PayoutCompletionService.fail` does not take the goal lock; the `EXECUTING` state guard is what
  prevents it racing a completion.
- [ ] Claim is compare-and-set READY→EXECUTING; cancel and execute have exactly one winner.
- [ ] Completion locks the goal before posting and marks EXECUTING→COMPLETED with a state guard.
- [ ] No external I/O (Paystack/SMS) runs inside the claim, completion or goal-lock transaction.
- [ ] The stuck sweep uses the `(payout_id) WHERE event='VERIFICATION_REQUIRED'` partial unique index;
  the flag is once-only across instances and the row lock prevents flagging a completed payout.
- [ ] Concurrent sweeps produce one `VERIFICATION_REQUIRED` event per payout. The alert is published
  after the event transaction commits, so it is at-most-once; a failed publish leaves the event and
  increments `payout.execution.stuck_alert_failed`.

## Failure-path evidence

| Scenario | Expected evidence |
| --- | --- |
| Provider failure | FAILED, no ledger, no auto-retry |
| Provider timeout/unknown | EXECUTING, `PROVIDER_UNKNOWN`, alert, no re-send |
| Completion persistence failure | EXECUTING, stuck alert, provider reference retained |
| Fee changed | FAILED `PAYOUT_FEE_MISMATCH`, no provider call, no ledger |
| Cancel before execution | CANCELLED, reservation released, no ledger |
| Cancel/execute race | one winner, one provider call, no duplicate ledger |
| Duplicate request key | one payout, one posting set |
| Notification SMS failure | alert, payout unchanged, retry sends once |
| Frozen goal | READY stays READY, execution blocked, alert summary/per-payout |
| Stuck sweep | one event/alert per payout, no provider call/ledger |

## How to review

1. Read the prerequisites and confirm the evidence commands.
2. Check the posting path line by line against the money checklist; use `PayoutPosting` and
   `LedgerRepository.postPayout` as the source of truth.
3. Check the notification path against the unique key and failure/retry behavior.
4. Confirm every claimed side effect in a state transition has code or a named task.
5. Record findings in `docs/reviews/FEAT-002.md` using `specs/_templates/review.md`: severity,
   location, reference (AC/INV/Article), action. Blockers are fixed test-first before sign-off.
6. Sign off explicitly for `ledger/` and `payments/`, naming the reviewed commit and date.

## Out of scope / follow-ups

- Refunds, recovery, loss allocation and operator reconciliation are FEAT-005 (ADR 0013), not this
  release. The review should confirm no partial refund flow is implied.
- Live Paystack transfer verification is T301/T302 and remains blocked by the starter-business tier
  and transfers OTP; the adapter is fake-tested only.
- No operator completion/notification-retry API exists; `docs/runbooks/payout-operations.md` records
  the manual escalation path.
- T365 is the actual CODEOWNER review and sign-off; this plan is an input, not a substitute.
