# Implementation plan: FEAT-002 Payouts

| Field | Value |
| --- | --- |
| Spec | `specs/features/002-payouts/spec.md` |
| Status | Ready |
| Author | Ahmed Zubairu |

## Summary

Add a payout module that reserves partial amounts against the settled available balance (ADR 0014),
collects approval from a designated member by SMS OTP when the goal rule triggers (ADR 0016),
enforces a mandatory hold and freeze, and executes through a worker with database idempotency and a
single ledger posting on completion. Contributor visibility, reports and sent notifications are part
of the feature because Article V.2 requires them. No payout posting is merged without a fresh
CODEOWNER review of the posting.

## Constitution check

| Article | Does this plan comply? | Notes |
| --- | --- | --- |
| I. Money is exact and traceable | Yes | Integer minor units; reservations derived from payout states; one ledger set on completion; fresh CODEOWNER review. |
| II. Spec first | Yes | Spec/ADR/plan/tasks land before payout code; OpenAPI in the implementation PR. |
| III. Test first | Yes | Every AC/INV has a test; provider/SMS fakes. |
| IV. Idempotent and consistent | Yes | Request key unique per goal; body-conflict 409; provider reference unique; goal locks; worker CAS. |
| V. Trust is a feature | Yes | Locked destination, reservations, settled eligibility, member approval, mandatory hold, freeze, contributor reports/notifications and visible settings. |
| VI. Guest first, low bandwidth | Yes | Approver needs no account; mobile-first pages. |
| VII. Simple until proven otherwise | Yes | One module, one provider adapter, no new datastore. |
| VIII. Security and privacy | Yes | Encrypted approver/destination phones; hashed approval values; scoped access; rate limits; decryption only for notifications. |
| IX. Observable | Yes | Phone-free events, metrics, reports/notification alerts. |

## Technical context

- Modules: new `payout`; `goal` for payout settings/approver; `identity` for SMS/OTP reuse; `payments`
  for quote/transfer; `ledger` for the completion posting; `web` for organizer, approver and public
  payout visibility.
- New dependencies: none.
- Data: V9 adds `payouts`, `payout_approvals`, `payout_reports`, `payout_notifications`,
  `payout_events` and goal approver fields; no balance columns; GHS only.

## Design

### Domain and data

- `goals`: approver name/phone ciphertext/hash/set-at; payout settings (`hold` and approver) can be
  set before the first payout.
- `payouts`: id, goal_id, requested_by, amount_minor_units, fee_minor_units, net_to_beneficiary,
  currency, state, idempotency_key, provider_reference, timestamps, failure_code.
  Active states reserve their amount; the available query subtracts them.
- `payout_approvals`: hashed token/code, expiry, attempts, consumed.
- `payout_reports`: payout_id, reason, optional contribution id, created_at.
- `payout_notifications`: payout_id, contribution_id, payer_phone_hash, sent_at; unique per contributor per payout.
- `payout_events`: append-only history.
- Request and completion take `SELECT ... FOR UPDATE` on the goal; the worker claims READY with a
  compare-and-set/SKIP LOCKED. Unique `(goal_id, idempotency_key)` and provider reference.
- Ledger on COMPLETED: goal pool debit X; partner held credit X-G; payout fee credit G only if
  positive; PAYOUT/PAYOUT_FEE entry types.

### API

- Goal creation approver fields; `PATCH /organizer/goals/{id}/payout-settings` before first payout.
- Payout quote/request/list/read/cancel under `/organizer`.
- Public goal payout view and `POST /public/goals/{slug}/payouts/{id}/report`.
- Approval token/verify/resend/decline under `/payout-approvals`.
- Public `payoutRules` adds the approver name only. OpenAPI is updated in the implementation PR.

### Sequence for the main path

1. Organizer requests a quote; available =
   `max(0, min(pool, max(0, settled − completed payout total)) − active reservations)`; fee/net are
   returned.
2. A request with an idempotency key locks the goal, re-checks available, reserves X, creates
   REQUESTED, and sends the approval SMS if required; otherwise APPROVED.
3. Approval (or auto) starts the mandatory HOLD; expiry makes READY.
4. The worker claims READY with a compare-and-set and goal lock, re-checks freeze and fee, calls the
   provider once; success posts the ledger and notifies funded contributors; failure releases the
   reservation; unknown remains EXECUTING for verification.
5. The public goal page shows the payout during hold and after sending; reports alert operators.
6. Cancel is CAS-guarded before EXECUTING; no auto retry.

## Test strategy

| Criterion | Test level | Test class or file | Notes |
| --- | --- | --- | --- |
| AC-PAYOUT-001 | Integration | `PayoutRequestIT` | Partial, quote |
| AC-PAYOUT-002 | Integration | `PayoutEligibilityIT` | Settled/suspense |
| AC-PAYOUT-003 | Unit + Integration | `PayoutFeeTest`, `PayoutRequestIT` | Fee/net, zero fee |
| AC-PAYOUT-004 | Integration | `PayoutApprovalIT` | Required/auto/decline |
| AC-PAYOUT-005 | Integration | `PayoutApprovalIT` | Wrong/expired/reused/resend |
| AC-PAYOUT-006 | Integration + Contract | `GoalPayoutSettingsIT` | Approver/settings/privacy |
| AC-PAYOUT-007 | Integration | `PayoutHoldIT` | Mandatory hold/freeze |
| AC-PAYOUT-008 | Integration | `PayoutCancelIT` | Pre/post execute |
| AC-PAYOUT-009 | Integration | `PayoutIdempotencyIT` | Same/different body |
| AC-PAYOUT-010 | Integration | `PayoutExecutionIT` | Worker, ledger, failure/unknown |
| AC-PAYOUT-011 | Integration | `PayoutOwnershipIT` | Own goals/tokens |
| AC-PAYOUT-012 | E2E | `web/e2e/payout.spec.ts` | Organizer + approver |
| AC-PAYOUT-013 | Integration | `PayoutReservationIT` | Concurrent reservations |
| AC-PAYOUT-014 | Integration + E2E | `PayoutVisibilityIT` | Public view/report/notify |
| AC-PAYOUT-015 | Integration | `PayoutFeeRecheckIT` | Fee mismatch before transfer |
| AC-PAYOUT-016 | Integration | `PayoutHoldIT` | Zero hold rejected |
| AC-PAYOUT-017 | Integration | `PayoutApprovalIT` | Resend/expiry cancel |
| AC-PAYOUT-018 | Integration | `PayoutIdempotencyIT` | 409 conflict |
| AC-PAYOUT-019 | Integration | `PayoutWorkerIT` | Cancel/execute CAS |
| AC-PAYOUT-020 | Integration | `PayoutRequestIT` | Non-OFF goal without approver |
| AC-PAYOUT-021 | Integration | `PayoutRequestIT` | Request fee/net row |
| AC-PAYOUT-022 | Integration | `PayoutRequestIT` | Request reservations |
| AC-PAYOUT-023 | Integration | `PayoutRequestIT` | Hold expiry/freeze request |
| AC-PAYOUT-024 | Integration | `PayoutApprovalIT` | Attempt exhaustion releases reservation |
| AC-PAYOUT-025 | Integration | `PayoutContributorIT` | Public payout view/report |
| AC-PAYOUT-026 | Integration | `PayoutContributorIT` | Completion notification once |
| INV-PAYOUT-001..008 | Integration | `PayoutInvariantsTest` | One per invariant |

## Migrations

- `V9__payout_approver_and_payouts.sql`: additive tables/columns; nullable approver/settings fields;
  no destructive change.
- `V10__payout_ledger_link.sql`: link ledger payout postings to their payout for goal-pool balance;
  additive, with a payout-link check and one-line-per-account uniqueness.
- Backward compatible: yes. Legacy goals with non-OFF rules or zero hold must set payout settings
  before their first payout.

## Rollout

- `collections.payout.enabled` default false; disabled routes return **404 only** (not 404/503).
- Observability: phone-free events, metrics, provider/SMS/notification alerts, report alerts.
- Rollback: disable the flag; no destructive migration.

## Risks

| Risk | Mitigation |
| --- | --- |
| Double spend across in-flight payouts | Active-state reservations under goal lock; re-check at completion; concurrency test |
| Provider fee/API differs | Sandbox spike; re-quote before transfer; fail before provider call |
| Contributor notification cost/decryption | Unique payer notifications; decrypt only for delivery; fake SMS; alerts |
| Approver unreachable | Resend/expiry cancel; organizer can request again; operator follow-up |
| Legacy zero-hold/non-OFF goals | Payout settings endpoint; explicit 422 until set |
| Freeze/cancel race | Worker CAS and goal lock; no client execute endpoint |
| Reporting abuse | Rate limits, scoped contribution proof, operator alerts |
| Payout posting error | Fresh CODEOWNER review of the posting before merge |

## Complexity tracking

None.
