# Feature 005: Refunds and operator tooling

| Field | Value |
| --- | --- |
| Feature ID | FEAT-005 |
| Status | In review |
| Owner | Ahmed Zubairu |
| Area code | REFUND, OPS |
| Created | 2026-11-13 |
| Related | ADR 0013 (refund and loss allocation), ADR 0017 (operator identity and MFA), ADR 0007 (platform fee), ADR 0012/0014 (settlement), `specs/domain/contribution-state-machine.md`, `specs/domain/settlement.md`, `docs/runbooks/payout-operations.md` |

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
   `PAYOUT_IN_FLIGHT` naming the conflicting payout id/state. The operator retries after it reaches
   a terminal state; a stuck `EXECUTING` payout is resolved through the payout operations runbook
   (and the P2 operator action), never by forcing a refund.
2. **Operator-only.** Every refund and reconciliation action requires an authenticated operator with
   the relevant role. The operator supplies a reason; actions are scoped to the contribution and
   cannot name a different destination or amount than the policy computes.
3. **One active refund.** A contribution has at most one active (`PENDING`, `PROVIDER_PENDING`,
   `SUCCEEDED`) refund, enforced by a partial unique index. Concurrent requests return the original
   or `409 REFUND_IN_PROGRESS`.
4. **Reserve before provider.** A normal pre-payout refund reserves the goal amount `A` — not the
   fee components — against the goal's available balance before any provider call, so a concurrent
   payout cannot spend it. A suspense refund reserves the suspense item and does not reduce goal
   availability (that money was never in the pool). The reservation is released on a definitive
   provider failure.
5. **No ledger before provider success.** The provider refund is called after the refund row commits,
   outside the goal lock. Only a confirmed provider success posts the one `REVERSAL` set and moves the
   contribution `CONFIRMED → REFUNDED`. A pending/unknown provider result leaves the refund
   `PROVIDER_PENDING` with the reservation held and raises an alert; it is never posted twice.
6. **Exact ledger.** The policy is a **full refund of `A + P + F`** (goal amount, platform fee,
   processing fee). On confirmed success, one `REVERSAL` set debits `GOAL_POOL` (`A`) or `SUSPENSE`
   (`A`) for a suspense item, `PLATFORM_FEE` (`P`) and `FEE` (`F`), and credits `PARTNER_HELD` the
   gross amount Paystack refunds (`A + P + F`). When Paystack retains `F`, a separate
   `LOSS_ALLOCATION` set debits `PLATFORM_LOSS` (`F`) and credits `BANK` (`F`) with the refund
   reason, funding the shortfall so the contributor still receives the full amount; when Paystack
   returns `F`, no loss set posts. Every set balances, uses integer minor units and GHS, and no
   account goes negative. On success the reversal replaces the reservation, so goal availability
   drops once — not once for the reservation and again for the posting.
7. **Idempotency.** `Idempotency-Key` is required, unique per contribution. The same key and body
   returns the original refund; a different body returns `409 IDEMPOTENCY_CONFLICT`. Provider
   duplicates are resolved by the provider refund reference, never by a second refund call.
8. **Suspense resolution.** A reconciliation item is resolved exactly once by refund, reclassification
   or miscellaneous income. Reclassification debits `SUSPENSE` and credits `GOAL_POOL`; income debits
   `SUSPENSE` and credits the dedicated platform-revenue account defined by the suspense-slice ADR;
   both require an operator reason and audit. A refund from suspense debits `SUSPENSE` (not
   `GOAL_POOL`) and does not post the goal amount a second time.
9. **Audit.** Every request, provider result, state transition, reason and resolution is appended to
   an immutable audit/event table. Operator responses and logs never contain phone numbers.
10. **Configuration.** The misc-income timeout, refund provider timeout and operator role source are
    configuration, defaulting conservatively; changing a fee or timeout requires an amendment here.
11. **Post-payout requests.** P1 returns `REFUND_AFTER_PAYOUT`; support records the request out of
    band and follows ADR 0013's manual recovery. No ledger or provider refund automation runs until
    the P2 recovery slice.

## Acceptance criteria

| ID | Priority | Given / When / Then | Test level |
| --- | --- | --- | --- |
| AC-REFUND-001 | P1 | Given an eligible confirmed contribution and an authorized operator, when a full pre-payout refund is requested, then a refund is created in `PENDING` with the goal amount `A` reserved (nothing reserved against the goal for a suspense item) and no ledger/provider call has happened inside the transaction. | Integration |
| AC-REFUND-002 | P1 | Given a pending refund, when the provider confirms success, then exactly one balanced `REVERSAL` set posts, the contribution becomes `REFUNDED`, the reservation is released and payout availability excludes it. | Integration |
| AC-REFUND-003 | P1 | Given a provider definitive failure, when the refund result is processed, then no ledger entry posts, the contribution stays `CONFIRMED`, the reservation is released and the refund is `FAILED` with an alert. | Integration |
| AC-REFUND-004 | P1 | Given a provider timeout/unknown, when the refund is recorded, then it stays `PROVIDER_PENDING`, the reservation remains held, an alert is raised and no ledger posts until verification. | Integration |
| AC-REFUND-005 | P1 | Given any active payout or a completed payout for the goal, when a refund is requested, then `409 PAYOUT_IN_FLIGHT` (naming the payout id and state) or `REFUND_AFTER_PAYOUT` is returned and no refund/provider call is created. | Integration |
| AC-REFUND-006 | P1 | Given concurrent refund requests for one contribution, when they race, then exactly one active refund/provider call exists and the other returns the original or `REFUND_IN_PROGRESS`. | Integration |
| AC-REFUND-007 | P1 | Given the accepted full-refund policy, when a refund succeeds, then one `REVERSAL` set debits `A` from `GOAL_POOL` or `SUSPENSE`, `P` from `PLATFORM_FEE` and `F` from `FEE`, credits `PARTNER_HELD` the gross `A + P + F` Paystack refunds, and — only when Paystack retains `F` — a separate `LOSS_ALLOCATION` set debits `PLATFORM_LOSS` and credits `BANK` for `F`; every set nets to zero and no account goes negative. | Integration |
| AC-REFUND-008 | P1 | Given a non-operator, foreign contribution or phone in the audit payload, when the action is attempted, then it is unauthorized/not found and no phone is stored or returned. | Integration |
| AC-REFUND-009 | P2 | Given a completed payout, when the operator opens a post-payout recovery request, then the request and recovery outcome are audited without changing the goal pool until a loss is approved. | Integration |
| AC-REFUND-010 | P2 | Given an unrecoverable post-payout shortfall, when an authorized operator approves a loss, then one `LOSS_ALLOCATION` posting moves the shortfall to `PLATFORM_LOSS` with a reason; no negative pool. | Integration |
| AC-OPS-001 | P1 | Given suspended/late contributions, when an operator lists the reconciliation queue, then each item shows its amount, age, goal and reference without phone numbers. | Integration |
| AC-OPS-002 | P1 | Given a suspense item, when an operator reclassifies it to the goal pool with a reason, then a balanced `SUSPENSE → GOAL_POOL` posting and immutable audit event are written exactly once. | Integration |
| AC-OPS-003 | P1 | Given a suspense item, when an operator refunds it, then the suspense amount is reversed/refunded once, the contribution becomes `REFUNDED` and no goal pool credit is left behind. | Integration |
| AC-OPS-004 | P1 | Given an aged suspense item past the configured timeout, when an operator records miscellaneous income with a reason, then `SUSPENSE` is debited, the dedicated platform-revenue account (defined by the suspense-slice ADR) is credited and the item cannot be resolved again. | Integration |
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
- Ledger: one `REVERSAL` set on confirmed provider success for the full-refund policy. It debits
  `GOAL_POOL` (`A`) for a normal contribution or `SUSPENSE` (`A`) for a suspended/late contribution,
  `PLATFORM_FEE` (`P`) and `FEE` (`F`), and credits `PARTNER_HELD` the gross `A + P + F` Paystack
  refunds. When Paystack retains `F`, a separate `LOSS_ALLOCATION` set debits `PLATFORM_LOSS` (`F`)
  and credits `BANK` (`F`) with the refund reason. Every set nets to zero and no account goes
  negative.
- On success the ledger reversal replaces the refund reservation: availability falls once, not twice.
  Suspense reclassification: debit `SUSPENSE`, credit `GOAL_POOL`. Misc income: debit `SUSPENSE`,
  credit the platform revenue account. No set may leave `GOAL_POOL` or `SUSPENSE` negative.
- Payout eligibility excludes `REFUNDED` contributions and active refund reservations.
- Invariants touched: INV-LEDGER-001/002/003/004/007, INV-STATE-001/002/003, INV-SETTLE-001/002/004.

## API impact

- `POST /operator/auth/login`, `POST /operator/auth/mfa`, `POST /operator/auth/logout` (separate
  operator session; password + TOTP; no public registration or self-signup)
- `POST /operator/contributions/{id}/refund` (operator auth, `Idempotency-Key`, reason)
- `GET /operator/refunds/{id}` and a queue/list
- `GET /operator/reconciliation/pending`
- `POST /operator/reconciliation/{contributionId}/reclassify|refund|income`
- New operator security scheme and schemas in `specs/api/openapi.yaml`
- Additive; no change to public/organizer payout endpoints. No public refund endpoint in P1.

## Security, privacy and fraud

- Operator identity, roles and MFA are defined by ADR 0017: a separate per-person operator account,
  password + TOTP, short-lived opaque session, and no shared bearer secret in client code. SSO is
  deferred.
- Refunds return only to the original payer; no refund destination is accepted.
- Reasons and evidence are stored; phone numbers are never in operator responses, events, logs or
  tickets. If evidence requires a phone, access is restricted and audited.
- Dual control is required for miscellaneous income (P1) and loss allocation (P2); recommended for
  reclassification. Rate-limit operator and login attempts, lock accounts after repeated failures,
  and alert on unusual volume.
- A compromised operator session must not be able to create a payout or change a destination.

## Observability

- Events: operator login/MFA success and failure, refund requested, provider result, succeeded,
  failed, pending, reconciliation resolved, loss allocated.
- Metrics: refunds requested/succeeded/failed/pending, refund amount, reconciliation queue depth and
  age, operator actions by type, operator auth failures, loss amount.
- Alerts: refund pending beyond threshold, provider failure, suspense aging, unmatched settlement,
  repeated operator/login authorization failures.

## Non-functional requirements

- Refund request p95 < 800 ms excluding the provider; the provider call is after commit and never
  holds a goal/payout row lock.
- Operator queue p95 < 500 ms for the default page; no full-table scans without pagination.
- Provider timeouts are bounded; unknown results are verifiable and never retried blindly.
- All money arithmetic is integer minor units; tests are deterministic with an injected clock and
  provider fake.

## Resolved decisions

Recorded 2026-11-14:

1. **Refund fee policy — full refund.** A pre-payout refund returns `A + P + F` (goal amount,
   platform fee, processing fee) to the original payer, and the checkout/receipt discloses the full
   refund. The plan must verify in the Paystack sandbox whether Paystack returns its processing fee
   with the refund. If it retains `F`, the platform funds the difference so the contributor still
   receives the gross `A + P + F`, and a separate `LOSS_ALLOCATION` set books `F` to `PLATFORM_LOSS`
   with the refund reason. This verification is a plan prerequisite, not an open question.
2. **Operator identity — separate, per person, MFA.** Operators get their own identity with password
   + TOTP, not an organizer role or a shared token. SSO is deferred; see ADR 0017.
3. **Operator-only initiation in P1.** Contributors and organizers ask support; there is no public or
   organizer refund endpoint. Self-service refunds are P2.
4. **In-flight payouts block refunds.** Any payout in `REQUESTED`, `APPROVED`, `HOLD`, `READY` or
   `EXECUTING` returns `409 PAYOUT_IN_FLIGHT` naming the payout id and state. A stuck `EXECUTING`
   payout is cleared through `docs/runbooks/payout-operations.md` and the P2 operator action, never by
   forcing a refund. A completed payout returns `REFUND_AFTER_PAYOUT`.
5. **Post-payout recovery is P2.** P1 records the request through support and follows ADR 0013's
   manual recovery; no automated post-payout ledger or provider action.
6. **Dedicated platform-revenue account, deferred to the suspense slice.** Miscellaneous income
   debits `SUSPENSE` and credits a dedicated platform-revenue account; `PLATFORM_FEE` is not reused.
   The account and its ADR land in that slice before AC-OPS-004 is implemented.

## Definition of ready

- [x] Every P1 story has at least one criterion
- [x] No open question blocks a P1 story
- [x] Failure modes each map to a criterion or an explicit gap
- [x] API impact listed; OpenAPI changes land in the implementation PR
- [ ] Constitution articles touched are named in the plan
- [x] Operator auth ADR accepted (ADR 0017)
- [x] Refund fee policy accepted (full `A + P + F`, `PLATFORM_LOSS` fallback)
