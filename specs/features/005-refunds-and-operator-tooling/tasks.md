# Tasks: FEAT-005 Refunds and operator tooling

Order is test-first. Tick a task only when its tests are green and the pull request is merged.
Provider tests use the fake only; the sandbox is a manual spike.

## Setup

- [ ] T401 Spike Paystack refunds in the sandbox: request/response for a full `A + P + F` refund,
  whether the processing fee is returned, refund statuses and the reference format; record it in
  `docs/runbooks/refund-operations.md`. Do not refund a real contributor.
- [ ] T402 Add operator/refund config and `RefundConfigIT` (`enabled`, provider `none|fake|paystack`,
  timeouts, suspense income age, session TTL/idle, lockout).
- [ ] T403 Add V12/V13 migrations and the operator/refund/reconciliation schemas and paths to
  `specs/api/openapi.yaml`; no behavior yet.
- [ ] T404 Add the fake Paystack refund server, fixed TOTP clock and refund test builders.
- [ ] T405 Write ADR 0018 (refund shortfall and `LOSS_ALLOCATION` shape); it supersedes only ADR
  0013's retained-refund-fee line. Leave ADR 0013's text unchanged.
- [ ] T406 Add deploy-time first-operator provisioning from a one-time token and require
  `OPERATOR_TOTP_ENCRYPTION_KEY` with no default; no migration contains a password, TOTP secret or
  token.

## Slice 1 (P1): Operator identity and sessions

Criteria: AC-REFUND-008, AC-OPS-005, AC-OPS-009, AC-OPS-010

- [ ] T410 Write failing `OperatorTotpTest` and `OperatorPasswordTest` (RFC 6238 vectors, skew,
  replay, PBKDF2 parameters, timing-safe compare).
- [ ] T411 Implement TOTP/Base32, recovery codes and password hashing until T410 is green.
- [ ] T412 Write failing `OperatorAuthIT` (login, MFA, wrong/expired/replayed code, lockout, session
  TTL/idle, logout, no public registration, audit).
- [ ] T413 Implement operator tables, repositories, auth endpoints, session service, filter and
  per-IP login/MFA rate limits until T412 is green.
- [ ] T414 Write failing `OperatorAuthorizationIT` (roles, disabled account, revoked session, distinct
  approver for income).
- [ ] T415 Implement roles, dual-control checks and admin account/role endpoints until T414 is green.
- [ ] T416 Add auth metrics/alerts and phone-free audit assertions.
- [ ] T417 Refactor; keep the suite green.
- [ ] T418 Write failing `OperatorStepUpIT` for AC-OPS-010 (income approval, loss allocation/role
  change with a TOTP older than five minutes).
- [ ] T419 Implement the recent-TOTP step-up gate until T418 is green.

## Slice 2 (P1): Refund request, reservation and provider

Criteria: AC-REFUND-001, AC-REFUND-003, AC-REFUND-004, AC-REFUND-005, AC-REFUND-006,
AC-REFUND-008; AC-REFUND-007 starts here

- [ ] T420 Write failing `RefundRequestIT` for AC-001/005/006 (reservation before provider, active
  payout block with id/state, failed/cancelled payouts allow, a completed payout blocks every
  contribution, concurrent and idempotent requests).
- [ ] T421 Implement the refund state machine, repository and request service with the goal lock,
  eligibility checks, reservation and audit until T420 is green.
- [ ] T422 Write failing `RefundProviderIT` for AC-003/004 (definitive failure, timeout/unknown,
  operator verification; fake only).
- [ ] T423 Implement the `payments` refund port/adapter, after-commit call, state transitions and
  verify endpoint until T422 is green.
- [ ] T424 Write failing `RefundPostingIT` for AC-002/007 (one full `A + P + F` reversal,
  retained-fee `LOSS_ALLOCATION` crediting `PARTNER_HELD`, both sets in one transaction, `REFUNDED`,
  availability drops once).
- [ ] T425 Implement the refund posting helper, success transaction, contribution transition and
  reservation replacement until T424 is green.
- [ ] T426 Write failing `RefundAuthorizationIT` for AC-REFUND-008 (non-operator, foreign
  contribution, phone in payload).
- [ ] T427 Implement operator scoping and redaction until T426 is green.
- [ ] T428 Add refund events, metrics and alerts (requested, pending, failed, succeeded, age).
- [ ] T429 Refactor; keep the suite green.

## Slice 3 (P1): Suspense reconciliation

Criteria: AC-OPS-001, AC-OPS-002, AC-OPS-003, AC-OPS-004, AC-OPS-009

- [ ] T430 Write failing `ReconciliationQueueIT` for AC-OPS-001 (amount, age, goal, reference, no
  phones).
- [ ] T431 Implement the pending-queue query and endpoint until T430 is green.
- [ ] T432 Write failing `SuspenseReconciliationIT` for AC-OPS-002/003/006 (reclassify, refund from
  suspense, one resolution winner).
- [ ] T433 Implement reclassification, suspense refund and the resolution guard until T432 is green.
- [ ] T434 Write ADR 0019 (dedicated platform-revenue account); it names the account symbol and
  migration number. Add the refund mapping to the ledger domain spec.
- [ ] T435 Write failing `SuspenseIncomeIT` for AC-OPS-004/009 (timeout, dedicated account, distinct
  proposer/approver).
- [ ] T436 Implement the account and migration named by ADR 0019, the two-step income flow and
  timeout gate until T435 is green.
- [ ] T437 Refactor; keep the suite green.

## Slice 4 (P1): Minimal operator console

No new criteria; this slice proves the P1 operator journey end-to-end.

- [ ] T440 Build the operator sign-in and TOTP enrollment pages plus BFF routes.
- [ ] T441 Build the reconciliation queue, refund action and resolution forms.
- [ ] T442 Write the Playwright E2E for operator login, refund and suspense resolution.
- [ ] T443 Refactor; keep lint, build, budget and E2E green.

## Slice 5 (P2): Deferred

Criteria: AC-REFUND-009, AC-REFUND-010, AC-OPS-007, AC-OPS-008

- [ ] T450 Post-payout recovery request and loss allocation.
- [ ] T451 Settlement CSV ingestion and unmatched-line resolution.
- [ ] T452 Payout and notification operator review.

## Hardening

- [ ] T490 Failure-path tests for every row in the spec's failure table, including failed/cancelled
  payouts allowing a refund and a completed payout blocking every contribution.
- [ ] T491 Phone-free event/metric/alert tests and auth abuse tests (lockout, TOTP stuffing, role
  escalation, dual-control bypass, stale step-up).
- [ ] T492 Run `make check`, web checks and E2E; remove every `@Disabled` marker for P1.
- [ ] T493 Update the spec status and `specs/README.md` to Implemented.
- [ ] T494 Fresh CODEOWNER review of the refund posting before merge.
- [ ] T495 Write `docs/runbooks/refund-operations.md` and the operator identity/account-recovery
  runbook.
- [ ] T496 Record the sandbox fee-return sign-off and complete the live provider enablement checklist.
