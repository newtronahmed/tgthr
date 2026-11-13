# Feature 005: Refunds and operator tooling

| Field | Value |
| --- | --- |
| Feature ID | FEAT-005 |
| Status | Draft |
| Owner | Ahmed Zubairu |
| Area code | REFUND, OPS |
| Created | 2026-11-13 |
| Related | ADR 0013 (refund and loss allocation), ADR 0007 (platform fee), ADR 0012/0014 (settlement), `specs/domain/contribution-state-machine.md`, `specs/domain/settlement.md`, `docs/runbooks/payout-operations.md` |

## Problem and outcome

A contribution can need reversal after payment: an operator error, a duplicate charge, a dispute, or
fraud. Before a payout the goal pool can be reduced safely; after a payout the beneficiary has the
money and recovery is a separate manual process. Late payments to closed goals are parked in
`SUSPENSE` and currently have no operator workflow.

When this feature works, an authenticated operator can refund an eligible contribution before payout
with one auditable ledger reversal and one provider refund, resolve `SUSPENSE` items without silently
inflating a goal, and every action is attributable, idempotent and safe against concurrent payouts.

## Actors

- **Operator/finance**: investigates exceptions, requests refunds, resolves suspense and records
  loss decisions.
- **Organizer**: asks support for a refund; can see the resulting contribution state.
- **Contributor (guest)**: paid the original transaction; the refund returns to the original payer.
- **CODEOWNER (`ledger/`, `payments/`)**: reviews and signs off money movements.
- **Support**: triages refund requests and gathers evidence.

## User stories

| Priority | Story |
| --- | --- |
| P1 | As an authenticated operator, I can refund a confirmed contribution before any payout, so a mistaken or disputed payment is returned once with a traceable ledger reversal. |
| P1 | As an operator, I can see the reconciliation queue for late/closed-goal contributions and resolve each item by refund, reclassification to the goal pool, or miscellaneous income with a reason. |
| P1 | As a CODEOWNER, I can audit every refund and reconciliation action, its actor, reason, provider result and ledger effect, so no money moves silently. |
| P2 | As an operator, I can open a post-payout recovery request and record beneficiary recovery or a loss allocation when recovery fails. |
| P2 | As an operator, I can ingest a settlement report and resolve unmatched provider lines from the same queue. |
| P2 | As an operator, I can review payout reports, stuck payouts and failed notifications from the operator surface. |

## Scope

**In scope (P1)**

- Operator-authenticated refund request for a **confirmed contribution before payout**.
- A refund state machine, idempotency key, reservation of the refund amount, provider refund call
  outside the transaction, and one `REVERSAL` ledger set on confirmed provider success.
- `REFUNDED` contribution state and exclusion from payout availability/settled eligibility.
- Operator reconciliation queue for `requires_reconciliation = true` / `SUSPENSE` contributions:
  refund, reclassify to the goal pool, or record miscellaneous income after the configured timeout.
- Immutable operator audit events, reason, actor, approval (where required), metrics and alerts.
- OpenAPI operator endpoints and a minimal operator surface (API first; no public refund endpoint).

**Out of scope**

- Post-payout refunds/recovery and loss allocation: P2 (ADR 0013 workflow).
- Settlement CSV ingestion, chargeback ingestion and unmatched-settlement actions: P2.
- Contributor self-service refund requests and organizer-initiated refunds: P2/support-assisted.
- Partial refunds: P2 (P1 is full refund of the eligible amount only).
- Refunds to a different destination: prohibited; Paystack returns to the original payer.
- Automated provider polling, retries and bulk refunds.

## Rules

1. **Eligibility.** A refund is allowed only for a `CONFIRMED` contribution with at least one
   successful ledger posting, no active refund, and **no payout in `REQUESTED`, `APPROVED`, `HOLD`,
   `READY` or `EXECUTING` for the same goal**. A completed payout makes the refund a post-payout
   recovery (P2), refused here with `REFUND_AFTER_PAYOUT`. An in-flight payout returns
   `PAYOUT_IN_FLIGHT`.
2. **Operator-only.** Every refund and reconciliation action requires an authenticated operator with
   the relevant role. The operator supplies a reason; actions are scoped to the contribution and
   cannot name a different destination or amount than the policy computes.
3. **One active refund.** A contribution has at most one active (`PENDING`, `PROVIDER_PENDING`,
   `SUCCEEDED`) refund, enforced by a partial unique index. Concurrent requests return the original
   or `409 REFUND_IN_PROGRESS`.
4. **Reserve before provider.** A requested refund reserves its amount against the goal's available
   balance immediately, before any provider call, so a concurrent payout cannot spend it. The
   reservation is released on a definitive provider failure.
5. **No ledger before provider success.** The provider refund is called after the refund row commits,
   outside the goal lock. Only a confirmed provider success posts the one `REVERSAL` set and moves the
   contribution `CONFIRMED → REFUNDED`. A pending/unknown provider result leaves the refund
   `PROVIDER_PENDING` with the reservation held and raises an alert; it is never posted twice.
6. **Exact ledger.** The refund reverses the contribution's goal amount and the fee components
   allowed by the fee policy; every set balances, uses integer minor units and GHS, and the goal pool
   never goes negative. The fee policy is an open P1 decision below.
7. **Idempotency.** `Idempotency-Key` is required, unique per contribution. The same key and body
   returns the original refund; a different body returns `409 IDEMPOTENCY_CONFLICT`. Provider
   duplicates are resolved by the provider refund reference, never by a second refund call.
8. **Suspense resolution.** A reconciliation item is resolved exactly once by refund, reclassification
   or miscellaneous income. Reclassification debits `SUSPENSE` and credits `GOAL_POOL`; income debits
   `SUSPENSE` and credits the platform revenue account; both require an operator reason and audit.
   A refund from suspense follows the same provider/ledger flow and does not post the goal amount a
   second time.
9. **Audit.** Every request, provider result, state transition, reason and resolution is appended to
   an immutable audit/event table. Operator responses and logs never contain phone numbers.
10. **Configuration.** The misc-income timeout, refund provider timeout and operator role source are
    configuration, defaulting conservatively; changing a fee or timeout requires an amendment here.

## Acceptance criteria

| ID | Priority | Given / When / Then | Test level |
| --- | --- | --- | --- |
| AC-REFUND-001 | P1 | Given an eligible confirmed contribution and an authorized operator, when a full pre-payout refund is requested, then a refund is created in `PENDING` with the policy amount reserved and no ledger/provider call has happened inside the transaction. | Integration |
| AC-REFUND-002 | P1 | Given a pending refund, when the provider confirms success, then exactly one balanced `REVERSAL` set posts, the contribution becomes `REFUNDED`, the reservation is released and payout availability excludes it. | Integration |
| AC-REFUND-003 | P1 | Given a provider definitive failure, when the refund result is processed, then no ledger entry posts, the contribution stays `CONFIRMED`, the reservation is released and the refund is `FAILED` with an alert. | Integration |
| AC-REFUND-004 | P1 | Given a provider timeout/unknown, when the refund is recorded, then it stays `PROVIDER_PENDING`, the reservation remains held, an alert is raised and no ledger posts until verification. | Integration |
| AC-REFUND-005 | P1 | Given any active payout or a completed payout for the goal, when a refund is requested, then `409 PAYOUT_IN_FLIGHT` or `REFUND_AFTER_PAYOUT` is returned and no refund/provider call is created. | Integration |
| AC-REFUND-006 | P1 | Given concurrent refund requests for one contribution, when they race, then exactly one active refund/provider call exists and the other returns the original or `REFUND_IN_PROGRESS`. | Integration |
| AC-REFUND-007 | P1 | Given the fee policy, when a refund succeeds, then the goal, platform-fee and processing-fee components reverse exactly, the posting set nets to zero and `GOAL_POOL` never goes negative. | Integration |
| AC-REFUND-008 | P1 | Given a non-operator, foreign contribution or phone in the audit payload, when the action is attempted, then it is unauthorized/not found and no phone is stored or returned. | Integration |
| AC-REFUND-009 | P2 | Given a completed payout, when the operator opens a post-payout recovery request, then the request and recovery outcome are audited without changing the goal pool until a loss is approved. | Integration |
| AC-REFUND-010 | P2 | Given an unrecoverable post-payout shortfall, when an authorized operator approves a loss, then one `LOSS_ALLOCATION` posting moves the shortfall to `PLATFORM_LOSS` with a reason; no negative pool. | Integration |
| AC-OPS-001 | P1 | Given suspended/late contributions, when an operator lists the reconciliation queue, then each item shows its amount, age, goal and reference without phone numbers. | Integration |
| AC-OPS-002 | P1 | Given a suspense item, when an operator reclassifies it to the goal pool with a reason, then a balanced `SUSPENSE → GOAL_POOL` posting and immutable audit event are written exactly once. | Integration |
| AC-OPS-003 | P1 | Given a suspense item, when an operator refunds it, then the suspense amount is reversed/refunded once, the contribution becomes `REFUNDED` and no goal pool credit is left behind. | Integration |
| AC-OPS-004 | P1 | Given an aged suspense item past the configured timeout, when an operator records miscellaneous income with a reason, then `SUSPENSE` is debited, platform revenue is credited and the item cannot be resolved again. | Integration |
| AC-OPS-005 | P1 | Given any operator action, when it is requested without an operator role, a reason, or an idempotency key, then it is rejected and no state/ledger change occurs. | Integration |
| AC-OPS-006 | P1 | Given two operators resolving the same item or a retry, when they race, then one resolution wins, the other is rejected, and the aging/count metrics and alerts reflect the remaining queue. | Integration |
| AC-OPS-007 | P2 | Given a settlement report file, when an operator uploads it, then batches/items are created idempotently, unmatched lines raise alerts and no duplicate contribution settlement is accepted. | Integration |
| AC-OPS-008 | P2 | Given a reported/stuck payout or failed notification, when an operator reviews it, then the evidence and retry/completion action are audited without re-executing a transfer or double-posting. | Integration |

## Failure modes and edge cases

| Situation | Expected behavior | Criterion |
| --- | --- | --- |
| Contribution not confirmed | 409 `REFUND_NOT_ELIGIBLE`; no refund | AC-REFUND-001 |
| Any active payout for the goal | 409 `PAYOUT_IN_FLIGHT`; no refund | AC-REFUND-005 |
| Payout already completed | 409 `REFUND_AFTER_PAYOUT`; recovery path P2 | AC-REFUND-005 |
| Duplicate request key, same body | Return the original refund | AC-REFUND-006 |
| Duplicate key, different amount | 409 `IDEMPOTENCY_CONFLICT` | AC-REFUND-006 |
| Provider refund fails | No ledger; release reservation; alert; retry with a new key | AC-REFUND-003 |
| Provider timeout/unknown | Hold reservation; `PROVIDER_PENDING`; verify; no blind retry | AC-REFUND-004 |
| Provider reports success twice | One ledger set, one `REFUNDED` transition | AC-REFUND-002 |
| Refund would overdraw `GOAL_POOL` | Refuse; operator reconciles the shortfall; no negative pool | AC-REFUND-007 |
| Suspense item resolved twice | One winner; second 409; audit unchanged | AC-OPS-006 |
| Misc-income before timeout | 409 `SUSPENSE_NOT_AGED`; no posting | AC-OPS-004 |
| Unauthorized operator | 401/403; no data/phone | AC-REFUND-008/AC-OPS-005 |
| Chargeback after refund | Alert; no second refund; settlement/P2 handling | gap: AC-OPS-007/P2 |

## Data and ledger impact

- New `refunds` table: contribution, policy amounts, state (`PENDING`, `PROVIDER_PENDING`, `SUCCEEDED`,
  `FAILED`), provider reference, idempotency key, operator, reason, timestamps; partial unique index
  for one active refund per contribution.
- New append-only `refund_events` and `operator_actions` audit tables.
- `contributions.state` uses the existing `REFUNDED`; no new contribution state is required while a
  refund is pending; availability subtracts active refunds.
- Ledger: one `REVERSAL` set on confirmed success. The exact account split depends on the open fee
  policy: goal amount `A`, platform fee `P`, processing fee `F`, all or a subset of
  `A + P + F` debited from `GOAL_POOL`/`PLATFORM_FEE`/`FEE` and credited to `PARTNER_HELD`.
  Suspense reclassification: debit `SUSPENSE`, credit `GOAL_POOL`. Misc income: debit `SUSPENSE`,
  credit the platform revenue account. No set may leave `GOAL_POOL` or `SUSPENSE` negative.
- Payout eligibility excludes `REFUNDED` contributions and active refund reservations.
- Invariants touched: INV-LEDGER-001/002/003/004/007, INV-STATE-001/002/003, INV-SETTLE-001/002/004.

## API impact

- `POST /operator/contributions/{id}/refund` (operator auth, `Idempotency-Key`, reason)
- `GET /operator/refunds/{id}` and a queue/list
- `GET /operator/reconciliation/pending`
- `POST /operator/reconciliation/{contributionId}/reclassify|refund|income`
- New operator security scheme and schemas in `specs/api/openapi.yaml`
- Additive; no change to public/organizer payout endpoints. No public refund endpoint in P1.

## Security, privacy and fraud

- Operator identity and role are a new concern; the plan must add an ADR for the operator auth
  mechanism and approval model. No shared bearer secret in client code.
- Refunds return only to the original payer; no refund destination is accepted.
- Reasons and evidence are stored; phone numbers are never in operator responses, events, logs or
  tickets. If evidence requires a phone, access is restricted and audited.
- Dual control is required for loss allocation (P2) and recommended for reclassification/income;
  rate-limit operator actions and alert on unusual volume.
- A compromised operator token must not be able to create a payout or change a destination.

## Observability

- Events: refund requested, provider result, succeeded, failed, pending, reconciliation resolved,
  loss allocated.
- Metrics: refunds requested/succeeded/failed/pending, refund amount, reconciliation queue depth and
  age, operator actions by type, loss amount.
- Alerts: refund pending beyond threshold, provider failure, suspense aging, unmatched settlement,
  repeated operator action/authorization failures.

## Non-functional requirements

- Refund request p95 < 800 ms excluding the provider; the provider call is after commit and never
  holds a goal/payout row lock.
- Operator queue p95 < 500 ms for the default page; no full-table scans without pagination.
- Provider timeouts are bounded; unknown results are verifiable and never retried blindly.
- All money arithmetic is integer minor units; tests are deterministic with an injected clock and
  provider fake.

## Open questions

- [NEEDS CLARIFICATION: Refund fee policy — does a pre-payout refund return only the goal amount + platform fee (processing fee non-refundable), or also the processing fee with the platform absorbing any provider shortfall? What does the checkout/receipt disclose?] Owner: Ahmed Zubairu. Needed by: before the FEAT-005 plan.
- [NEEDS CLARIFICATION: Operator authentication/roles — is the operator a separate identity with MFA/SSO, an internal CLI with a scoped token, or part of the organizer identity with a new role? Which actions need dual control?] Owner: Ahmed Zubairu. Needed by: before the FEAT-005 plan (new ADR).
- [NEEDS CLARIFICATION: Is operator-only initiation acceptable for P1, with contributor/organizer requests handled by support, or is a contributor request path required in the first slice?] Owner: Ahmed Zubairu. Needed by: plan.
- [NEEDS CLARIFICATION: Confirm that any in-flight payout blocks a refund (the conservative rule above), rather than cancelling/rolling back the payout reservation.] Owner: Ahmed Zubairu. Needed by: plan.
- [NEEDS CLARIFICATION: Post-payout recovery and loss allocation are P2; confirm they can wait until after the pre-payout/suspense slice.] Owner: Ahmed Zubairu. Needed by: plan.
- [NEEDS CLARIFICATION: Miscellaneous-income revenue account — reuse `PLATFORM_FEE` with `ADJUSTMENT`, or add a dedicated platform-revenue account via ADR?] Owner: Ahmed Zubairu. Needed by: plan.

## Definition of ready

- [ ] Every P1 story has at least one criterion
- [ ] No open question blocks a P1 story
- [ ] Failure modes map to a criterion or an explicit gap
- [ ] API impact reflected in `openapi.yaml`
- [ ] Constitution articles touched named in the plan
- [ ] Operator auth ADR accepted
- [ ] Refund fee policy accepted
