# Feature 002: Payouts

| Field | Value |
| --- | --- |
| Feature ID | FEAT-002 |
| Status | Ready |
| Owner | Ahmed Zubairu |
| Area code | PAYOUT |
| Created | 2026-11-09 |
| Related | ADR 0005, 0006, 0012, 0013, 0014, 0016; `specs/domain/settlement.md`, `specs/domain/payout-state-machine.md`, `specs/domain/organizer-identity.md` |

## Problem and outcome

Organizers can collect money but cannot withdraw it. A payout must not overdraw unsettled or
suspended funds, must not double-spend in-flight payouts, must respect the goal's approval, hold and
freeze safeguards, and must be visible and reportable to contributors (Constitution V.2). When this
works, the organizer requests a partial payout up to the reserved settled balance, the designated
member approves by SMS OTP when required, the fee is quoted and goal-borne, and the ledger records
the transfer once on completion.

## Actors

- Organizer: requests and cancels payouts, sees the fee, net and state.
- Designated approver: a group member with no account; approves or declines by SMS OTP.
- Contributor: can see a payout during its hold, report a problem, and is notified when it is sent.
- Payment partner: quotes the transfer fee and executes the transfer.
- Operator: investigates failures and reconciles; operator tooling is out of scope here.

## User stories

| Priority | Story |
| --- | --- |
| P1 | As an organizer, I request a partial payout up to the reserved settled available balance, so I can use collected funds without waiting for every contribution to settle. |
| P1 | As an organizer, I designate an approver and a positive hold window, so a second person controls payouts when the goal rule requires it. |
| P1 | As a designated approver, I approve or decline a payout by entering an SMS code, so I can stop a suspicious transfer without an account. |
| P1 | As a contributor, I see a payout during its hold, can report a problem, and am notified when it is sent (Article V.2). |
| P1 | As an organizer, I see the exact fee, net and hold window before I request, so the goal's money is not a surprise. |
| P1 | As an organizer, I can cancel before execution and cannot cancel in flight, so I cannot accidentally duplicate a transfer. |
| P1 | As the platform, I reserve in-flight payouts, execute once, post the ledger only on completion, and never auto-retry a failure. |
| P2 | As an organizer, I see payout history and failure reasons. |
| P2 | As an operator, I have a reconciliation queue for exceptions (FEAT-005). |

## Scope

**In scope**

- Reserved partial payout quote/request against settled funds; approver and hold settings; SMS OTP
  approval/decline/resend; contributor visibility, reporting and sent notifications.
- Hold, freeze, cancellation, execution worker, provider transfer adapter, idempotency and ledger.
- Organizer payout UI and the public approver page.
- Privacy, metrics, alerts and tests.

**Out of scope**

- Refund/recovery workflows: FEAT-005.
- Operator reconciliation dashboard, settlement CSV ingestion and automatic retries: later.
- Changing the approver/hold after the first payout; destination changes remain prohibited.
- Multi-currency: later ADR.

## Rules

1. **Eligibility and reservations.** Let
   `available = max(0, min(goal pool balance, max(0, settled goal amount − completed payout total)) − active payout reservations)`
   where active means `REQUESTED`, `APPROVED`, `HOLD`, `READY` or `EXECUTING`, and the completed
   payout total is the sum of all-in amounts already paid from this goal. A request reserves X under
   a `SELECT ... FOR UPDATE` lock on the goal; cancel/failure releases it; completion re-checks and
   posts under the same lock. Confirmed-but-unsettled and `requires_reconciliation` contributions
   are excluded (ADR 0014).
2. **Partial payouts.** The organizer may request any amount up to `available`; X is the all-in
   goal-pool debit; multiple payouts are allowed.
3. **Fee.** The provider quotes G. The goal bears it: beneficiary net is `X − G`, and X must exceed
   G. Re-quote before transfer; if G changed, transition `READY → FAILED` with
   `PAYOUT_FEE_MISMATCH` and make no provider call. The `PAYOUT_FEE` ledger line is posted only when
   G > 0. Amounts are integer minor units.
4. **Approver and hold settings.** A goal with a non-`OFF` rule requires an approver name and phone
   before the first payout; supply them at creation or via
   `PATCH /organizer/goals/{id}/payout-settings`. A payout request before they are set returns 422
   `APPROVER_REQUIRED`. The approver phone must be valid, must differ from the organizer, is
   encrypted with a keyed lookup hash, and is never public; only the name is public. The same
   settings endpoint sets a positive hold window. A hold below one minute is refused with 422
   `HOLD_REQUIRED`; zero-hold legacy goals cannot request payouts until fixed.
5. **Approval trigger.** Approval is required for `ALWAYS`, or `ABOVE_THRESHOLD` with
   `X >= secondApproverThresholdPesewas` (the live API field; the DB stores minor units). Otherwise
   the payout is auto-approved. A required payout stays `REQUESTED` until approved.
6. **Approval challenge.** The approver receives an SMS link token and a six-digit code. Token and
   code are hashed; code lifetime 10 minutes; five attempts; 60-second resend; token lifetime 24
   hours. Resend invalidates the previous token and code. Both are single-use. Wrong, expired,
   over-attempted or reused values return 401. Rate limits are per phone and IP. If the token
   expires, `REQUESTED → CANCELLED (approval expired)` and the organizer is notified; exhausting the
   five attempts also cancels and releases the reservation; a new payout request is required. The
   approver may decline, which cancels the payout.
7. **Hold.** After approval, `HOLD` for the positive hold window, then `READY`. Held payouts can be
   cancelled. The hold is mandatory (Article V.2/ADR 0005).
8. **Freeze and close.** A `FROZEN` goal rejects new payout requests with 409 and blocks
   `READY → EXECUTING`; the payout stays `READY` until unfrozen or cancelled. `CLOSED` allows
   payouts. No freeze/unfreeze endpoint in this feature.
9. **Cancellation.** The organizer may cancel `REQUESTED`, `APPROVED`, `HOLD` or `READY`; it becomes
   `CANCELLED` with no ledger entry. `EXECUTING` and terminal states cannot be cancelled.
10. **Idempotency.** A request requires an `Idempotency-Key`, unique per goal. The same key with the
    same amount returns the original payout; the same key with a different amount returns 409
    `IDEMPOTENCY_CONFLICT`. The server re-quotes the fee; a changed fee is handled by the pre-transfer
    check (AC-PAYOUT-015), not as an idempotency conflict. The provider transfer reference is unique;
    the ledger posting set is unique.
11. **Execution.** A scheduled **payout worker** claims `READY` rows with a compare-and-set and the
    goal row lock; there is no client execute endpoint. It re-checks the fee and goal freeze before
    calling the provider once. Success → `COMPLETED`; definitive failure → `FAILED`; unknown/timeout
    stays `EXECUTING` and is verified, never blindly re-executed. A `FAILED` payout is terminal; no
    automatic retry; a new request uses a new key.
12. **Ledger.** Only `COMPLETED` posts: debit `GOAL_POOL` X; credit `PARTNER_HELD` X−G; credit
    `PAYOUT_FEE` G if positive. No balance column; entries append-only.
13. **Ownership and privacy.** Organizers see only their own payouts; an approval token authorizes
    only its payout. Approver and destination phones never appear in responses or logs.
14. **Observability.** Phone-free events, metrics and alerts cover requests, approval, hold, fee
    checks, execution, failure and completion.
15. **Contributor visibility (Article V.2).** The public goal page shows a payout during `HOLD` and
    after it is sent (amount, fee, state, hold expiry; no phones). A contributor can submit a
    rate-limited report about a specific payout from the public page or receipt. On `COMPLETED`, the
    platform notifies contributors whose confirmed contributions funded the goal, using the stored
    payer phone (decrypted for delivery only), once per contributor per payout; notification
    failures alert and do not change the payout.

## Acceptance criteria

| ID | Priority | Given / When / Then | Test level |
| --- | --- | --- | --- |
| AC-PAYOUT-001 | P1 | Given settled available funds, when the organizer requests a valid partial amount, then a payout is created with the quoted fee/net and no amount above available is accepted. | Integration |
| AC-PAYOUT-002 | P1 | Given unsettled or reconciliation-flagged contributions, when a payout exceeds settled available, then 422 `INSUFFICIENT_SETTLED_BALANCE` and no payout. | Integration |
| AC-PAYOUT-003 | P1 | Given a payout quote, when accepted, then fee, net and goal debit are exact integer minor units and the goal bears the fee; a zero fee omits the fee line. | Unit + Integration |
| AC-PAYOUT-004 | P1 | Given an approval-triggering rule, when requested, then it stays REQUESTED until the approver approves; a decline cancels it. | Integration |
| AC-PAYOUT-005 | P1 | Given an approval challenge, when the code is wrong, expired, over-attempted or reused, then it is rejected; resend invalidates prior values; one approval works. | Integration |
| AC-PAYOUT-006 | P1 | Given a non-OFF rule, when created or set before first payout, then the approver is required, distinct, encrypted, and only the name is public. | Integration + Contract |
| AC-PAYOUT-007 | P1 | Given an approved payout, when the positive hold expires, then READY; a frozen goal blocks new requests and READY→EXECUTING until unfrozen. | Integration |
| AC-PAYOUT-008 | P1 | Given a payout before execution, when cancelled, then CANCELLED with no ledger; cancelling EXECUTING returns 409. | Integration |
| AC-PAYOUT-009 | P1 | Given a retry with the same key and body, then the original payout and no second posting; a different body returns 409. | Integration |
| AC-PAYOUT-010 | P1 | Given READY, when the worker succeeds, then COMPLETED with exactly one ledger set; failure is FAILED with none and no auto-retry. | Integration |
| AC-PAYOUT-011 | P1 | Given another organizer or a token for another payout, when they access it, then 404/401 and no data. | Integration |
| AC-PAYOUT-012 | P1 | Given the organizer web and approver page, when the organizer requests and the approver approves, then both see the state and no phone is displayed. | E2E |
| AC-PAYOUT-013 | P1 | Given in-flight payouts, when concurrent requests with different keys arrive, then goal-locked reservations prevent total X above available; cancel/failure releases. | Integration |
| AC-PAYOUT-014 | P1 | Given a held or completed payout, when a contributor views the goal, then amount/state/hold are visible, a report action exists, and a completion notifies funded contributors once. | Integration + E2E |
| AC-PAYOUT-015 | P1 | Given READY, when the provider fee differs from the quote, then FAILED `PAYOUT_FEE_MISMATCH` before any provider call. | Integration |
| AC-PAYOUT-016 | P1 | Given a hold window below one minute, when a payout is requested, then 422 `HOLD_REQUIRED` and no payout. | Integration |
| AC-PAYOUT-017 | P1 | Given an approval token/code, when resend or expiry occurs, then the previous values are invalid and an expired token cancels the payout; no stuck REQUESTED. | Integration |
| AC-PAYOUT-018 | P1 | Given the same idempotency key with a different amount, then 409 `IDEMPOTENCY_CONFLICT`. | Integration |
| AC-PAYOUT-019 | P1 | Given READY, when cancel and the execution worker race, then one compare-and-set wins; no duplicate provider call. | Integration |
| AC-PAYOUT-020 | P1 | Given a non-OFF goal without a configured approver, when a payout is requested, then 422 `APPROVER_REQUIRED` and no payout. | Integration |
| AC-PAYOUT-021 | P1 | Given a valid payout request, when accepted, then the stored fee and beneficiary net are exact integer minor units and the goal bears the fee. | Integration |
| AC-PAYOUT-022 | P1 | Given in-flight payouts, when concurrent requests arrive, then goal-locked reservations prevent total X above the settled available balance. | Integration |
| AC-PAYOUT-023 | P1 | Given a positive hold, when it expires, then the payout becomes READY; a FROZEN goal rejects a new request with 409. | Integration |
| AC-PAYOUT-024 | P1 | Given five wrong approval attempts, when the challenge is exhausted, then the payout is CANCELLED, its reservation released and the challenge consumed. | Integration |
| AC-PAYOUT-025 | P1 | Given a held or completed payout, when a contributor views the goal, then amount/fee/state/hold are visible, a report can be stored and alerted, and no phone is exposed. | Integration |
| AC-PAYOUT-026 | P1 | Given a completed payout, when funded contributors are notified, then each payer receives one notification and repeat processing does not duplicate it. | Integration |
| AC-PAYOUT-027 | P1 | Given an EXECUTING payout older than the stuck threshold, when the verification sweep runs, then it is flagged once with an alert and event, no provider call and no ledger posting, and it stays EXECUTING for operator verification. | Integration |

## Failure modes and edge cases

| Situation | Expected behavior | Criterion |
| --- | --- | --- |
| Amount above reserved available | 422 `INSUFFICIENT_SETTLED_BALANCE`, no payout | AC-PAYOUT-002/013 |
| Amount at/below fee | 422 `AMOUNT_TOO_LOW`, no payout | AC-PAYOUT-003 |
| Approver wrong/expired/reused | 401; challenge burned as required | AC-PAYOUT-005 |
| Approver declines | CANCELLED, no ledger | AC-PAYOUT-004 |
| Approval token expires | CANCELLED, organizer notified; new request | AC-PAYOUT-017 |
| Approver equals organizer | 422 at setup | AC-PAYOUT-006 |
| Approver not set before payout | 422 `APPROVER_REQUIRED`, no payout | AC-PAYOUT-020/004 |
| Zero hold | 422 `HOLD_REQUIRED` | AC-PAYOUT-016 |
| Goal FROZEN at request/execution | 409; payout stays READY | AC-PAYOUT-007 |
| Cancel after EXECUTING | 409 | AC-PAYOUT-008 |
| Duplicate key different amount | 409 | AC-PAYOUT-018 |
| Provider failure | FAILED, no ledger, no auto retry | AC-PAYOUT-010 |
| Provider timeout/unknown | EXECUTING until verification; stuck rows flagged once and never re-executed | AC-PAYOUT-010/027 |
| Fee changed | FAILED before provider, alert | AC-PAYOUT-015 |
| SMS/provider unavailable | 503/failed state; alert | AC-PAYOUT-004/010 |
| Contributor report | Rate-limited report stored and alerted; no payout change | AC-PAYOUT-025 |
| Notification failure | Alert; payout unchanged | AC-PAYOUT-026 |

## Data and ledger impact

- `goals`: approver name/phone ciphertext/hash/set-at; hold/payout settings endpoint for legacy
  goals.
- `payouts`: id, goal_id, requested_by, amount_minor_units, fee_minor_units, net, currency, state,
  idempotency_key, provider_reference, approved/hold/executing/completed/failed/cancelled
  timestamps, failure_code, created/updated.
- `payout_approvals`: hashed token/code, expiry, attempts, consumed.
- `payout_reports`: payout_id, reason, optional contribution id, created_at.
- `payout_notifications`: payout_id, contribution_id, payer_phone_hash, sent_at (unique per contributor per payout).
- `payout_events`: append-only.
- Ledger on COMPLETED: goal pool debit X; partner held credit X−G; payout fee credit G only if
  positive. No balance.
- Invariants: `INV-PAYOUT-001..008`, `INV-SETTLE-001`, `INV-LEDGER-007`.

## API impact

- Goal creation approver fields; `PATCH /organizer/goals/{id}/payout-settings` before first payout.
- `GET /organizer/goals/{goalId}/payout-quote`; `POST/GET /organizer/goals/{goalId}/payouts`;
  `GET /organizer/payouts/{id}`; `POST /organizer/payouts/{id}/cancel`.
- Public goal payout view and `POST /public/goals/{slug}/payouts/{id}/report`.
- `GET /payout-approvals/{token}`; `POST /payout-approvals/{token}/verify|resend|decline`.
- OpenAPI updated in the implementation PR. Backward compatible; legacy non-OFF/zero-hold goals
  must set payout settings before their first payout.

## Security, privacy and fraud

- Approver is independent and phone-verified; the organizer cannot approve alone when required.
- Approval values are hashed, expiring, attempt-limited, single-use and rate-limited; the approver
  phone is encrypted and never returned. Contributor notification decrypts the payer phone only for
  delivery; reports are rate-limited and do not reveal phones.
- Destination is locked; ownership and tokens are scoped; provider references and ledger sets are
  unique. In-flight reservations and goal locks prevent double spending; fee re-check prevents silent
  deductions.
- Colluding organizer/approver can still approve a legitimate-looking payout; hold/freeze/
  contributor reporting and operator reconciliation remain the safeguards.

## Observability

- Events: requested, approval sent/approved/declined/expired, hold started/expired, fee mismatch,
  reservation released, execution started, stuck/verification required, completed, failed,
  cancelled, report received, notifications sent/failed.
- Metrics: reserved/available amounts, approval latency/failures, provider latency/failures,
  notification failures, fee mismatches, stuck EXECUTING.
- Alerts: provider outage, fee mismatch, approval/notification spikes, stuck execution, reports.

## Non-functional requirements

- Quote p95 < 500 ms; request p95 < 800 ms excluding provider; approval SMS within 5 seconds;
  provider timeout never creates a duplicate transfer; all money integer minor units.

## Open questions

None. The four product decisions were made 2026-11-09: partial payouts, designated-member OTP
approver, goal/beneficiary bears the fee, freeze/cancel/no-auto-retry.

## Definition of ready

- [x] Every P1 story has at least one criterion
- [x] No open question blocks a P1 story
- [x] Failure modes each map to a criterion
- [x] API impact listed; OpenAPI changes land in the implementation PR
- [x] Constitution articles touched are named in the plan
