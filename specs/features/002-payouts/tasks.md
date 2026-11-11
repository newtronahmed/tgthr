# Tasks: FEAT-002 Payouts

Order is test-first. Tick a task only when its tests are green and the pull request is merged.

## Setup

- [ ] T301 Spike the Paystack transfer API in the sandbox; record request/response, fee schedule and idempotency reference format (no real provider in tests)
- [ ] T302 Add quote/transfer operations to the payment adapter with a fake and fee re-check
- [x] T303 Add goal approver fields and `PATCH /organizer/goals/{id}/payout-settings` (before first payout only); encrypt the phone; update OpenAPI
- [x] T304 Add V9: `payouts` (with reservation states), `payout_approvals`, `payout_reports`, `payout_notifications`, `payout_events`, goal approver/settings fields; append-only events; unique request/provider references
- [x] T305 Build the payout state machine with unit tests for every legal/illegal transition
- [ ] T306 Add payout config: feature flag, fee fallback, approval TTL/attempts/resend, minimum hold
- [ ] T307 Add fake SMS/approval and fake transfer adapters to the test harness
- [ ] T308 Add payout API schemas/paths to `specs/api/openapi.yaml`
- [ ] T309 Obtain a fresh CODEOWNER review plan for the payout posting and contributor notification paths

## Slice 1 (P1): Quote, eligibility, reservations and request

Criteria: AC-PAYOUT-001, AC-PAYOUT-002, AC-PAYOUT-003, AC-PAYOUT-013

- [x] T310 Write failing `PayoutRequestIT` cases for AC-001/002 (settled cap, unsettled/suspense excluded)
- [x] T311 Implement `available` integration and amount validation until T310 is green
- [ ] T312 Write failing full AC-003 case for the zero-fee ledger line (request fee/net is covered by AC-021)
- [x] T313 Implement the provider fee quote and request row (request-phase part done; full AC-003 ledger line remains T312)
- [ ] T314 Write failing full AC-013 case for cancel/failure release (request concurrency is covered by AC-022)
- [ ] T315 Implement reservations and completion re-check until T314 is green
- [ ] T316 Write failing full AC-009 case for no second posting (request idempotency is AC-018/T317)
- [x] T317 Implement the unique request key and different-amount conflict (request-phase part done; full AC-009 remains T316)
- [x] T318 Refactor; keep the suite green
- [ ] T319 Write and implement the payout-request `APPROVER_REQUIRED` gate for a non-OFF goal without a configured approver, and take the goal lock before inserting the payout (AC-PAYOUT-020; includes the PATCH-vs-payout concurrency test) (Slice 1)

## Slice 2 (P1): Approver, settings and approval

Criteria: AC-PAYOUT-004, AC-PAYOUT-005, AC-PAYOUT-006, AC-PAYOUT-017

- [x] T320 Write failing `GoalPayoutSettingsIT` for AC-006 (approver required/distinct/encrypted/public name, zero hold)
- [x] T321 Implement approver/hold settings until T320 is green
- [x] T322 Write failing `PayoutApprovalIT` for AC-004 (rule triggers, approve/decline)
- [x] T323 Implement approval challenge/token and state gating until T322 is green
- [x] T324 Write failing tests for AC-005/017 (wrong/expired/over-attempted/reused/resend/expiry)
- [x] T325 Implement approval security, resend invalidation and expiry cancellation until T324 is green
- [x] T326 Add approval SMS/alert metrics and phone-free logging
- [x] T327 Add per-approver/IP rate limiting to approval GET/verify/resend/decline
- [x] T328 Notify the organizer on approval expiry/exhaustion/decline (or explicitly defer the channel)

## Slice 3 (P1): Mandatory hold, freeze, cancellation and worker

Criteria: AC-PAYOUT-007, AC-PAYOUT-008, AC-PAYOUT-016, AC-PAYOUT-019

- [x] T330 Write failing `PayoutHoldIT`/`PayoutRequestIT` cases for AC-023/016 (positive hold, freeze blocks request, zero hold rejected)
- [x] T331 Implement hold/ready/freeze checks with goal locking until T330 is green
- [ ] T332 Write failing `PayoutCancelIT` for AC-008 (pre/post execute)
- [ ] T333 Implement cancellation guards/events until T332 is green
- [ ] T334 Write failing `PayoutWorkerIT` for AC-019 (cancel/execute compare-and-set)
- [ ] T335 Implement the READY claim/CAS worker path until T334 is green

## Slice 4 (P1): Execution, fee re-check, ledger and notifications

Criteria: AC-PAYOUT-010, AC-PAYOUT-014, AC-PAYOUT-015

- [x] T340 Write failing `PayoutExecutionIT` for AC-010/015 (success/failure/unknown/one ledger, fee mismatch before provider)
- [x] T341 Implement provider call, fee re-check, unique reference, completion posting and after-commit `notifyCompleted` call until T340 is green (fake provider; real adapter T302)
- [ ] T342 Write failing full AC-014 E2E for the public payout view/report UI (backend is covered by AC-025/026/T343)
- [x] T343 Implement public payout view, reports and funded-contributor notifications; group by `payer_phone_hash` and insert one notification per contributor, treating a unique conflict as already sent
- [x] T344 Add stuck-EXECUTING verification and alerts (AC-PAYOUT-027; scan flags once, no re-execution)
- [ ] T345 Refactor; keep the suite green

## Slice 5 (P1): Ownership and web

Criteria: AC-PAYOUT-011, AC-PAYOUT-012

- [ ] T350 Write failing ownership/token tests for AC-011; implement guards
- [ ] T351 Build organizer quote/request/history/cancel UI
- [ ] T352 Build approver `/approve/[token]` page (code, resend, decline)
- [ ] T353 Wire BFF routes with cookies/Origin and no phone exposure
- [ ] T354 Write the Playwright E2E for AC-012 (organizer + approver)
- [ ] T355 Refactor; keep lint/build/budget/E2E green

## Hardening

- [ ] T360 Failure-path tests for every row in the spec failure table
- [ ] T361 Phone-free event/metric/alert tests; abuse tests (approval pumping, token stuffing, cross-goal, report spam)
- [ ] T362 Provider sandbox and fee-mismatch runbook; notification/decryption operations
- [ ] T363 Run `make check`, web checks and E2E; remove every `@Disabled` marker for FEAT-002
- [ ] T364 Independent review at slice boundaries; record in `docs/reviews/FEAT-002.md`
- [ ] T365 Fresh CODEOWNER review of the payout posting before merge
- [ ] T366 Add a stuck-EXECUTING backlog gauge and validate `collections.payout.stuck-after` against the provider timeout
