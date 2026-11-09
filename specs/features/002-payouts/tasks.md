# Tasks: FEAT-002 Payouts

Order is test-first. Tick a task only when its tests are green and the pull request is merged.

## Setup

- [ ] T301 Spike the Paystack transfer API in the sandbox; record request/response, fee schedule and idempotency reference format (no real provider in tests)
- [ ] T302 Add quote/transfer operations to the payment adapter with a fake and fee re-check
- [ ] T303 Add goal approver fields and `PATCH /organizer/goals/{id}/payout-settings` (before first payout only); encrypt the phone; update OpenAPI
- [ ] T304 Add V9: `payouts` (with reservation states), `payout_approvals`, `payout_reports`, `payout_notifications`, `payout_events`, goal approver/settings fields; append-only events; unique request/provider references
- [ ] T305 Build the payout state machine with unit tests for every legal/illegal transition
- [ ] T306 Add payout config: feature flag, fee fallback, approval TTL/attempts/resend, minimum hold
- [ ] T307 Add fake SMS/approval and fake transfer adapters to the test harness
- [ ] T308 Add payout API schemas/paths to `specs/api/openapi.yaml`
- [ ] T309 Obtain a fresh CODEOWNER review plan for the payout posting and contributor notification paths

## Slice 1 (P1): Quote, eligibility, reservations and request

Criteria: AC-PAYOUT-001, AC-PAYOUT-002, AC-PAYOUT-003, AC-PAYOUT-013

- [ ] T310 Write failing `PayoutEligibilityIT` for AC-001/002 (settled cap, unsettled/suspense excluded)
- [ ] T311 Implement `available` integration and amount validation until T310 is green
- [ ] T312 Write failing `PayoutFeeTest`/`PayoutRequestIT` for AC-003 (fee, net, zero-fee line)
- [ ] T313 Implement the provider fee quote and request row until T312 is green
- [ ] T314 Write failing `PayoutReservationIT` for AC-013 (concurrent requests under goal lock, release on cancel/failure)
- [ ] T315 Implement reservations and completion re-check until T314 is green
- [ ] T316 Write failing `PayoutIdempotencyIT` for AC-009/018 (same/different body)
- [ ] T317 Implement the unique request key and body conflict until T316 is green
- [ ] T318 Refactor; keep the suite green

## Slice 2 (P1): Approver, settings and approval

Criteria: AC-PAYOUT-004, AC-PAYOUT-005, AC-PAYOUT-006, AC-PAYOUT-017

- [ ] T320 Write failing `GoalPayoutSettingsIT` for AC-006 (approver required/distinct/encrypted/public name, zero hold)
- [ ] T321 Implement approver/hold settings until T320 is green
- [ ] T322 Write failing `PayoutApprovalIT` for AC-004 (rule triggers, approve/decline)
- [ ] T323 Implement approval challenge/token and state gating until T322 is green
- [ ] T324 Write failing tests for AC-005/017 (wrong/expired/over-attempted/reused/resend/expiry)
- [ ] T325 Implement approval security, resend invalidation and expiry cancellation until T324 is green
- [ ] T326 Add approval SMS/alert metrics and phone-free logging

## Slice 3 (P1): Mandatory hold, freeze, cancellation and worker

Criteria: AC-PAYOUT-007, AC-PAYOUT-008, AC-PAYOUT-016, AC-PAYOUT-019

- [ ] T330 Write failing `PayoutHoldIT` for AC-007/016 (positive hold, freeze blocks request/execute, zero hold rejected)
- [ ] T331 Implement hold/ready/freeze checks with goal locking until T330 is green
- [ ] T332 Write failing `PayoutCancelIT` for AC-008 (pre/post execute)
- [ ] T333 Implement cancellation guards/events until T332 is green
- [ ] T334 Write failing `PayoutWorkerIT` for AC-019 (cancel/execute compare-and-set)
- [ ] T335 Implement the READY claim/CAS worker path until T334 is green

## Slice 4 (P1): Execution, fee re-check, ledger and notifications

Criteria: AC-PAYOUT-010, AC-PAYOUT-014, AC-PAYOUT-015

- [ ] T340 Write failing `PayoutExecutionIT` for AC-010/015 (success/failure/unknown/one ledger, fee mismatch before provider)
- [ ] T341 Implement provider call, fee re-check, unique reference and completion posting until T340 is green
- [ ] T342 Write failing `PayoutVisibilityIT` for AC-014 (public view, report, sent notifications)
- [ ] T343 Implement public payout view, reports and funded-contributor notifications until T342 is green
- [ ] T344 Add stuck-EXECUTING verification and alerts
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
