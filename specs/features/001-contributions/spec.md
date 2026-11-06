# Feature 001: Guest contribution

| Field | Value |
| --- | --- |
| Feature ID | FEAT-001 |
| Status | Ready |
| Owner | Ahmed Zubairu |
| Area code | CONTRIB |
| Created | 2026-11-04 |
| Related | specs/domain/ledger.md, specs/domain/contribution-state-machine.md, docs/adr/0004-database-enforced-idempotency.md, docs/adr/0006-paystack-payment-partner.md |

## Problem and outcome

People who want to support a funeral, wedding or group goal are sent a WhatsApp link, and most will not install an app or create an account. When this feature works, a guest can open the link, pay by mobile money in under 30 seconds, and see a receipt, while the system records each confirmed payment exactly once.

## Actors

- Guest: pays from a phone with no account.
- Organizer: owns the goal and watches contributions arrive.
- Payment partner: sends the MoMo prompt and reports the result by signed callback.
- System: records state and ledger entries.

## User stories

| Priority | Story |
| --- | --- |
| P1 | As a guest, I open a goal link, enter an amount, my name and my MoMo number, approve the prompt on my phone, and see a receipt. |
| P1 | As a guest, I see exactly what I will be charged before I pay. |
| P1 | As the system, I record every confirmed payment exactly once, even if the partner repeats or delays callbacks. |
| P2 | As a guest, I can give anonymously on the public feed. |
| P2 | As a guest whose payment fails or times out, I am told what happened and can try again. |

## Scope

**In scope**

- Public goal page data, contribution creation, status polling and receipt data.
- Provider callback handling, polling fallback and expiry.
- Public feed rules for anonymous and named gifts.

**Out of scope**

- Payouts and approvals (FEAT-002).
- Signed-in user history and claiming contributions (FEAT-004).
- Refunds (FEAT-005).
- Web push and SMS notifications.

## Rules

1. Amounts are integer pesewas in GHS.
2. A fixed-amount goal accepts only its fixed amount; an open goal accepts any amount at or above GHS 10 (1,000 pesewas).
3. The guest is shown contribution, processing fee, platform fee and total charged before paying. The contributor bears Paystack's processing fee and the platform's 1.5% service fee. The platform fee is 1.5% of the contribution rounded up to the nearest pesewa and is charged on top; the total is grossed up so the goal receives the full contribution and the platform receives its full fee (ADR 0007). The public goal page states that a 1.5% platform fee is added at checkout.
4. A goal accepts contributions only while it is open, unfrozen and before its deadline.
5. Only a confirmed contribution counts toward the goal total and posts ledger entries. The platform fee is not part of the goal total.
6. The payer's phone number is never returned by any public endpoint.
7. A PENDING contribution is checked with the partner after 5 minutes; the result is applied as CONFIRMED, FAILED or EXPIRED.
8. A valid late SUCCESS for an EXPIRED or FAILED contribution confirms it and raises an alert; a confirmed payment is never dropped.
9. The platform fee is platform revenue, recorded as a separate PLATFORM_FEE ledger entry at confirmation.

## Acceptance criteria

| ID | Priority | Given / When / Then | Test level |
| --- | --- | --- | --- |
| AC-CONTRIB-001 | P1 | Given an open goal, when a guest submits a valid amount, MoMo number and network, then a contribution is created in INITIATED, the provider prompt is requested, the state becomes PENDING, and the response returns the contribution id with state PENDING. | Integration |
| AC-CONTRIB-002 | P1 | Given a fixed-amount goal, when a guest submits a different amount, then the response is 422 with code AMOUNT_NOT_ALLOWED and no contribution exists. | Integration |
| AC-CONTRIB-003 | P1 | Given an open goal with a minimum amount, when a guest submits less than the minimum, then the response is 422 with code AMOUNT_TOO_LOW and no contribution exists. | Unit |
| AC-CONTRIB-004 | P1 | Given a PENDING contribution, when the provider sends the same SUCCESS callback twice, then the ledger holds exactly one posting set for that contribution and the state is CONFIRMED. | Integration |
| AC-CONTRIB-005 | P1 | Given a PENDING contribution, when a valid SUCCESS callback arrives, then in one transaction the state becomes CONFIRMED and the ledger entries are posted and net to zero, and the goal total rises by the contribution amount. | Integration |
| AC-CONTRIB-006 | P1 | Given a PENDING contribution, when a valid FAILED callback arrives, then the state becomes FAILED, no ledger entries exist for it, and the goal total is unchanged. | Integration |
| AC-CONTRIB-007 | P1 | Given a callback with a missing or invalid signature, when it is received, then the response is 401, no state changes, and a security event is logged. | Integration |
| AC-CONTRIB-008 | P1 | Given a PENDING contribution past its timeout, when the status job runs, then it asks the partner for the final status and applies the result: CONFIRMED if paid, otherwise EXPIRED. | Integration |
| AC-CONTRIB-009 | P2 | Given a FAILED or EXPIRED contribution, when the guest tries again, then a new contribution is created and the old one is not reused. | Integration |
| AC-CONTRIB-010 | P2 | Given a contribution marked anonymous, when the public feed is read, then it shows "An anonymous contributor gave GHS X" with no name, and the organizer view shows the real name. | Contract |
| AC-CONTRIB-011 | P1 | Given any public endpoint, when it responds, then the body contains no payer phone number. | Contract |
| AC-CONTRIB-012 | P1 | Given a goal that is closed, frozen or past its deadline, when a guest submits a contribution, then the response is 409 with code GOAL_NOT_ACCEPTING and no contribution exists. | Integration |
| AC-CONTRIB-013 | P1 | Given a PENDING contribution, when its status is read, then the state is returned without a receipt; given CONFIRMED, then the receipt is included with amount, processing fee, platform fee, total, reference and time. | Contract |
| AC-CONTRIB-014 | P1 | Given any allowed amount, when the fees are computed, then total equals amount plus processing fee plus platform fee, and the ledger entries for the event net to zero (property test over the allowed range). | Unit |
| AC-CONTRIB-015 | P1 | Given a create request retried with the same Idempotency-Key, when it is received again, then the original contribution is returned and no second provider prompt is sent. | Integration |
| AC-CONTRIB-016 | P1 | Given a contribution in EXPIRED or FAILED, when the partner sends a valid late SUCCESS callback or a poll returns SUCCESS, then the state becomes CONFIRMED, the ledger entries are posted exactly once, the goal total rises, and an alert is raised. | Integration |
| AC-CONTRIB-017 | P1 | Given any allowed amount, when the platform fee is computed, then it equals 1.5% of the contribution rounded up to the nearest pesewa, is charged on top, and does not reduce the goal amount. | Unit |
| AC-CONTRIB-018 | P1 | Given a contribution still INITIATED, when a valid SUCCESS callback arrives before the prompt was recorded as sent, then the contribution is confirmed, the posting set is posted exactly once, and no second prompt is sent. | Integration |
| AC-CONTRIB-019 | P1 | Given a prompt request that the partner rejects or times out, when the create request completes, then the contribution is FAILED, no ledger entries exist, and the response carries the FAILED state so the guest may retry. | Integration |
| AC-CONTRIB-020 | P1 | Given a signed callback for an unknown provider reference, when it arrives, then no contribution or ledger row changes, the event is logged, and an alert is raised. | Integration |
| AC-CONTRIB-021 | P1 | Given a signed callback whose amount differs from the contribution total, when it arrives, then the state and ledger are unchanged and an alert is raised for review. | Integration |

## Failure modes and edge cases

| Situation | Expected behavior | Criterion |
| --- | --- | --- |
| Partner times out when we request the prompt | Contribution moves to FAILED; guest may retry | AC-CONTRIB-019 |
| Callback arrives before our PENDING commit | Confirm from INITIATED and post once; the create path's later PROMPT_SENT is ignored, and never creates a second contribution | AC-CONTRIB-018 |
| Callback for an unknown provider reference | Logged and alerted, no state change | AC-CONTRIB-020 |
| Callback amount differs from the contribution amount | Not applied; alert raised for review | AC-CONTRIB-021 |
| Guest double-taps Pay | Same contribution returned | AC-CONTRIB-015 |
| Guest closes the page after approving | Callback still confirms; receipt reachable by contribution id | AC-CONTRIB-013 |
| Late SUCCESS after the contribution was marked EXPIRED or FAILED | Confirm with an alert; never drop a confirmed payment | AC-CONTRIB-016 |

## Data and ledger impact

- Entities created: Contribution, contribution event history.
- Ledger entries posted on confirmation: CONTRIBUTION, FEE and PLATFORM_FEE.
- Invariants touched: INV-LEDGER-001, INV-LEDGER-004, INV-STATE-001, INV-STATE-002.

## API impact

- Added: `GET /public/goals/{slug}`, `POST /public/goals/{slug}/contributions`, `GET /public/contributions/{id}`, `POST /webhooks/momo`.
- `ContributionStatus` adds `processingFeePesewas` and `platformFeePesewas`; `feePesewas` remains as their sum for compatibility.
- Backward compatibility: additive fields, no existing clients.
- Follow-up: the webhook header and payload in `openapi.yaml` are placeholders and must be replaced with Paystack's `x-paystack-signature` and callback payload in the adapter pull request (ADR 0006).

## Security, privacy and fraud

- Callbacks are verified by signature with a replay window.
- The contribution id is unguessable (random UUID) because it grants receipt access.
- Card-testing style abuse: rate limit contribution creation per IP and per MoMo number.
- The payer's number is stored encrypted and never leaves the backend except to the partner.

## Observability

- Events: contribution created, prompt requested, callback received (valid or rejected), state transition, expiry.
- Metrics: contributions by state, callback latency, time from PENDING to CONFIRMED, platform fees collected.
- Alerts: contribution PENDING beyond the timeout, unmatched callback, amount mismatch, late SUCCESS after EXPIRED or FAILED, reconciliation difference.

## Non-functional requirements

- The status endpoint is safe to poll every 2 seconds for up to 60 seconds per contribution.
- The public goal page needs no authentication and no cookies.
- At the 95th percentile, contribution creation responds within 1.5 seconds including the partner prompt request, and status reads respond within 300 milliseconds.

## Resolved decisions

| Date | Question | Decision |
| --- | --- | --- |
| 2026-11-05 | Minimum contribution on open goals | GHS 10 (1,000 pesewas) |
| 2026-11-05 | Provider timeout before a PENDING contribution is checked | 5 minutes |
| 2026-11-05 | Fee schedule and who bears it | Paystack Ghana's 1.95% local rate; the contributor bears it; the total is grossed up so the goal receives the full amount |
| 2026-11-05 | Late SUCCESS after EXPIRED or FAILED | Confirm with an alert; adds EXPIRED to CONFIRMED and FAILED to CONFIRMED |
| 2026-11-05 | Response-time targets at the 95th percentile | Create 1.5 seconds including the partner prompt; status 300 milliseconds |
| 2026-11-05 | Platform revenue model | 1.5% platform service fee on the contribution amount, rounded up to the nearest pesewa, paid by the contributor on top (ADR 0007) |

## Definition of ready

- [x] Every P1 story has at least one criterion
- [x] No open question blocks a P1 story (decisions recorded 2026-11-05)
- [x] Failure modes each map to a criterion or an explicit gap
- [x] API impact is reflected in `openapi.yaml`
- [x] Constitution articles touched are named in the plan
