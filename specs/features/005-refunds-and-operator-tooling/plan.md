# Implementation plan: FEAT-005 Refunds and operator tooling

| Field | Value |
| --- | --- |
| Spec | `specs/features/005-refunds-and-operator-tooling/spec.md` |
| Status | Draft (ready for review) |
| Author | Ahmed Zubairu |

## Summary

Add two P1 tracks: a separate operator identity (password + TOTP, roles, immutable audit) and a
pre-payout refund/reconciliation workflow. A refund reserves the goal amount under the goal lock,
calls Paystack only after the request commits, and posts one `REVERSAL` set only after the provider
confirms. The full `A + P + F` policy is implemented as a gross reversal; if Paystack retains `F`, a
separate `LOSS_ALLOCATION` set credits `PARTNER_HELD` for the fee the platform owes the partner, so the
contributor is made whole without a cash posting. Suspense items
are resolved through a queue by refund, reclassification or dual-controlled miscellaneous income.
Post-payout recovery and settlement ingestion remain P2. The main trade-off is more up-front
identity work, bought to avoid over-privileging organizer sessions and to make every money movement
attributable.

## Constitution check

| Article | Does this plan comply? | Notes |
| --- | --- | --- |
| I. Money is exact and traceable | Yes | Integer pesewas; reservation plus one balanced reversal; separate loss set; fresh CODEOWNER review of the posting. |
| II. Spec first | Yes | ADR 0017, spec, this plan and tasks land before refund code. |
| III. Test first | Yes | Every P1 criterion has a failing integration test first; provider fake only. |
| IV. Idempotent and consistent | Yes | Unique active-refund index, contribution idempotency key, goal lock, state compare-and-set, provider reference. |
| V. Trust is a feature | Yes | Operator attribution, reasons, immutable audit, full refund, queue visibility and alerts. |
| VI. Guest first, low bandwidth | Yes | Contributor needs no account; refund returns to the original payer. The operator console is desktop-friendly but lightweight. |
| VII. Simple until proven otherwise | Yes | One new operator module, one refund module, JDK PBKDF2/TOTP, no new datastore or provider. |
| VIII. Security and privacy | Yes | Separate short-lived operator session, TOTP, lockout, roles, dual control, phone-free responses/logs. |
| IX. Observable | Yes | Auth, refund and reconciliation events, metrics, queue-age and pending-provider alerts. |

## Technical context

- Modules touched: new `operator` (accounts, TOTP, sessions, roles, audit); new `refund` (refund
  state, request/reservation, reconciliation, posting); `payments` (Paystack refund adapter only —
  ADR 0006 keeps every Paystack call here); `contribution` (state transition and suspense queue);
  `ledger` (posting helper and revenue account); `payout` (availability subtracts active refunds);
  `web` (minimal operator console); `alerts` for operator alerts.
- New dependencies: none. Password hashing uses JDK PBKDF2-HMAC-SHA256 (600,000 iterations) and TOTP
  uses JDK HMAC-SHA1, as ADR 0017 permits when Argon2 is unavailable. The TOTP secret key comes from
  `OPERATOR_TOTP_ENCRYPTION_KEY`, a required environment variable with no default.
- Data: V12 operator identity/audit; V13 refunds/reconciliation/refund events; the suspense slice
  adds the dedicated revenue account named by ADR 0019 (working label `PLATFORM_REVENUE`) in the
  migration that ADR names. No balances stored.

## Design

### Domain and data

- `operators`: id, username (unique, case-insensitive), password_hash, totp_secret_ciphertext,
  status (`PENDING_ENROLLMENT`, `ACTIVE`, `DISABLED`), failed_attempts, locked_until, created_at,
  updated_at. Per-person only.
- `operator_roles`: operator_id, role (`REFUND_OPERATOR`, `RECONCILER`, `FINANCE_APPROVER`,
  `OPERATOR_ADMIN`), granted_by, granted_at; primary key on (operator_id, role).
- `operator_sessions`: operator_id, token_hash, created_at, last_seen_at, expires_at, revoked_at.
  Absolute 8 hours, 30-minute idle; separate cookie `tgthr_operator_session`, path `/operator`.
- `operator_mfa`: recovery code hashes and the last consumed TOTP step for replay rejection.
- `operator_auth_events` and `operator_actions`: append-only audit with actor, action, subject,
  reason, request id and before/after state. No phone numbers.
- `refunds`: id, contribution_id, goal_id, source_account (`GOAL_POOL` or `SUSPENSE`), amount `A`,
  platform_fee `P`, processing_fee `F`, total `A + P + F`, state (`PENDING`, `PROVIDER_PENDING`,
  `SUCCEEDED`, `FAILED`), idempotency_key, provider_reference, operator_id, reason, timestamps,
  failure_code. A partial unique index allows one active refund per contribution.
- `refund_events`: append-only request/provider/state events.
- `reconciliation_resolutions`: contribution_id unique, resolution (`REFUND`, `RECLASSIFY`,
  `INCOME`), proposer/approver, reason, posting_set_id, created_at. The queue excludes resolved items.
- Active refunds reserve `A` against the goal; `payout.available` subtracts them. A refund request
  takes the goal lock, like a payout request. On success the ledger debit replaces the reservation:
  availability falls once.
- Ledger: `ContributionPosting.refund` posts `REVERSAL`: debit `GOAL_POOL` or `SUSPENSE` `A`,
  `PLATFORM_FEE` `P`, `FEE` `F`; credit `PARTNER_HELD` `A + P + F`. When the partner retains `F`,
  `LOSS_ALLOCATION` debits `PLATFORM_LOSS` `F` and credits `PARTNER_HELD` `F` in the same
  transaction (ADR 0018). Zero-fee legs are omitted. Reclassification debits `SUSPENSE`, credits
  `GOAL_POOL`; income debits `SUSPENSE`, credits the account named by ADR 0019.
- Refund state machine: `PENDING → PROVIDER_PENDING → SUCCEEDED|FAILED`, `PENDING → SUCCEEDED|FAILED`.
  Only provider confirmation moves `CONFIRMED → REFUNDED`; posting never runs inside the request
  transaction.
- Suspense queue: `requires_reconciliation = true`, `state = CONFIRMED`, no resolution row. Income
  requires `now - updated_at >= collections.refunds.suspense-income-after`, a `RECONCILER` proposal
  and a different `FINANCE_APPROVER` approval.

### API

- Operator auth: `POST /operator/auth/login`, `POST /operator/auth/mfa`, `POST /operator/auth/logout`;
  admin account/role endpoints under `/operator/operators`; no public registration.
- Refunds: `POST /operator/contributions/{id}/refund`, `GET /operator/refunds/{id}`,
  `GET /operator/refunds`, `POST /operator/refunds/{id}/verify`.
- Reconciliation: `GET /operator/reconciliation/pending`,
  `POST /operator/reconciliation/{contributionId}/reclassify|refund|income`,
  `POST /operator/reconciliation/{contributionId}/income/approve`.
- New `operatorSession` cookie security scheme and schemas in `specs/api/openapi.yaml`. No public or
  organizer refund endpoint. OpenAPI changes land with the implementation PR.

### Sequence for the main path

1. `operator` verifies username/password, then a TOTP step, and issues a short-lived session with the
   operator's roles. Failed attempts are counted and locked; every result is audited.
2. `refund` authenticates the role, locks the goal and the contribution, and checks: `CONFIRMED`,
   not suspense for the normal path, no active refund, no payout in `REQUESTED..EXECUTING`, no
   `COMPLETED` payout. It computes `A`, `P`, `F` from the contribution, inserts `PENDING` with the
   idempotency key, reserves `A`, and writes the audit event. Commit.
3. After commit, `payments` calls the Paystack refund for the transaction reference and total. A
   definitive success starts a new transaction: lock the refund, post the reversal and — only when
   the fee is retained — the loss set **together**, transition the contribution to `REFUNDED`, mark
   `SUCCEEDED`, and publish the event. A definitive failure marks `FAILED` and releases the
   reservation. A timeout/unknown marks `PROVIDER_PENDING`, keeps the reservation and raises an
   alert.
4. A `REFUND_OPERATOR` uses `POST /operator/refunds/{id}/verify` with a reason to ask Paystack for
   the result; verification follows the same success/failure transitions, is audited, and never
   posts twice.
5. Suspense resolution: the queue lists unresolved contributions without phones. Reclassification and
   refund use the same posting/guard rules; income is a two-step propose/approve flow with distinct
   operators.
6. Payout availability subtracts `PENDING`/`PROVIDER_PENDING` refund reservations. A refund request
   waits for no active payout; a payout request sees the reduced availability.

## Test strategy

| Criterion | Test level | Test class or file | Notes |
| --- | --- | --- | --- |
| AC-REFUND-001 | Integration | `RefundRequestIT` | Reservation before provider; no ledger/provider in the request transaction. |
| AC-REFUND-002 | Integration | `RefundPostingIT` | One reversal, `REFUNDED`, availability once. |
| AC-REFUND-003 | Integration | `RefundProviderIT` | Definitive failure releases the reservation. |
| AC-REFUND-004 | Integration | `RefundProviderIT` | Unknown stays `PROVIDER_PENDING`; verify. |
| AC-REFUND-005 | Integration | `RefundRequestIT` | Active blocks with id/state; completed blocks every contribution; failed/cancelled allow. |
| AC-REFUND-006 | Integration | `RefundRequestIT` | Concurrent requests, one winner, idempotency conflict. |
| AC-REFUND-007 | Integration | `RefundPostingIT` | Full `A + P + F`; retained fee loss set; balanced; no account negative except the allowed `PARTNER_HELD` F obligation. |
| AC-REFUND-008 | Integration | `RefundAuthorizationIT` | Non-operator, foreign contribution, no phone. |
| AC-REFUND-009 | P2 | `PostPayoutRecoveryIT` | Deferred to the P2 slice. |
| AC-REFUND-010 | P2 | `PostPayoutRecoveryIT` | Deferred to the P2 slice. |
| AC-OPS-001 | Integration | `ReconciliationQueueIT` | No phone fields; age and reference. |
| AC-OPS-002 | Integration | `SuspenseReconciliationIT` | `SUSPENSE → GOAL_POOL` exactly once. |
| AC-OPS-003 | Integration | `SuspenseReconciliationIT` | Suspense refund debits `SUSPENSE`. |
| AC-OPS-004 | Integration | `SuspenseIncomeIT` | Timeout, dedicated revenue account, resolved once. |
| AC-OPS-005 | Integration | `OperatorAuthorizationIT` | Role, reason and idempotency key required. |
| AC-OPS-006 | Integration | `SuspenseReconciliationIT` | Concurrent resolution has one winner. |
| AC-OPS-007 | P2 | `SettlementUploadIT` | Deferred to the P2 slice. |
| AC-OPS-008 | P2 | `PayoutReviewIT` | Deferred to the P2 slice. |
| AC-OPS-009 | Integration | `SuspenseIncomeIT` | Same operator cannot propose and approve. |
| AC-OPS-010 | Integration | `OperatorStepUpIT` | Income/loss/role change needs a TOTP within five minutes. |
| INV-LEDGER-001/003/004/007 | Integration | `RefundPostingIT` | Balance, non-negative pool, idempotency, GHS. |
| INV-STATE-001/002/003 | Integration | `RefundPostingIT` | History append-only and legal transitions. |

Unit tests: `OperatorTotpTest` (RFC vectors, skew, replay), `OperatorPasswordTest` (PBKDF2
parameters, timing-safe compare), `RefundStateMachineTest` (every legal/illegal transition).

## Migrations

- `V12__operator_identity.sql`: operators, roles, sessions, MFA/recovery codes, auth events and
  operator actions; append-only triggers on audit tables.
- `V13__refunds_and_reconciliation.sql`: refunds, refund events, reconciliation resolutions, one
  active refund per contribution, queue indexes, provider reference unique.
- `V14` (working label): the suspense slice adds the dedicated revenue account named by ADR 0019
  (working label `PLATFORM_REVENUE`) to the ledger account check; no data rewrite. The ADR fixes the
  account symbol and migration number before implementation.
- Backward compatible: yes. No destructive change; existing organizer sessions and money tables are
  untouched.

## Rollout

- Flags/config: `collections.operator.enabled=false`, `collections.refunds.enabled=false`,
  `collections.refunds.provider=none`, `collections.refunds.suspense-income-after=P30D`,
  `collections.operator.session-ttl=P8H`, `collections.operator.idle-timeout=PT30M`,
  `collections.operator.max-failed-attempts=5`, `collections.operator.lockout=PT15M`.
- Required secret: `OPERATOR_TOTP_ENCRYPTION_KEY`, no default; startup fails when operator auth is
  enabled without it. The first `OPERATOR_ADMIN` is provisioned at deploy time from a one-time token;
  no migration contains a password, TOTP secret or token.
- Disabling `refunds.enabled` blocks new requests but keeps read, verify and completion paths open so
  no `PENDING`/`PROVIDER_PENDING` refund is stranded.
- Enable `provider=paystack` only after T401 records the sandbox fee-return result and the live key is
  configured. Tests use the fake only.
- Observability: operator auth failures/lockouts, refund requested/succeeded/failed/pending, refund
  amount, queue depth/age, dual-control rejections, provider latency; alerts for pending refunds,
  provider failures, aging suspense and repeated auth failures.
- Rollback: disable the flags; no destructive migration. Verify/complete remains available.

## Risks

| Risk | Mitigation |
| --- | --- |
| Paystack refund API differs or retains the fee | T401 sandbox spike records request/response and fee behavior; fake covers both branches; the loss set credits `PARTNER_HELD` for the fee owed to the partner (ADR 0018). |
| Double posting or double refund | Partial unique index, refund state CAS, provider reference unique, posting only after confirmation. |
| Reservation and ledger double-count availability | Reservation is `A`; success posts the pool debit and marks the refund terminal in one transaction. |
| Refund blocked forever by a stuck `EXECUTING` payout | `PAYOUT_IN_FLIGHT` names the payout; runbook exit; no forced refund. |
| Operator lockout or lost TOTP device | Deploy-time bootstrap and admin reset with a one-time token; no secrets in migrations; runbook; audited. |
| Password/TOTP implementation error | JDK primitives with RFC vectors, timing-safe compare, replay rejection, lockout. |
| Phone leakage in operator responses/logs | DTOs exclude phones; tests assert phone-free JSON and logs; audit stores ids only. |
| Refund after settlement creates a cash difference | Ledger reverses the obligation; settlement reconciliation (P2) resolves the cash side; alert. |
| Suspense income abuse | Timeout, distinct proposer/approver, reason, immutable audit, alerts. |
| Operator console scope creep | API first; the console slice is limited to login, queue, refund and resolution actions. |

## Complexity tracking

None.
