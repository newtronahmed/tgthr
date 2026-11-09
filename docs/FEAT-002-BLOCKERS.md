# FEAT-002 Implementation Checklist

**Status:** Phase 1 implemented and CODEOWNER-signed off (2026-11-08); Phase 2 needs its feature spec first

This document tracks the implementation tasks required to enforce the architectural decisions made for FEAT-002 (payouts). All decisions are now documented in ADRs 0011, 0012, 0013 and domain specs.

---

## Phase 1: Foundation (blocks all FEAT-002 code)

### Currency Migration (ADR 0011)

- [x] **T201:** Run migration `V5__currency_and_settlement.sql` against test database
- [x] **T202:** Update `Money` record: replace `pesewas` with `minorUnits`, add `currencyCode` field
- [x] **T203:** Update `Money` constructor: validate currency code (3 uppercase letters, non-null)
- [x] **T204:** Update `Money.plus()`: throw `IllegalArgumentException` if currencies mismatch
- [x] **T205:** Add `Money.of(long minorUnits, String currencyCode)` factory method
- [x] **T206:** Find/replace all `Money.ofPesewas(x)` → `Money.of(x, "GHS")` in FEAT-001/003 code
- [x] **T207:** Property test: `Money.plus()` with mismatched currencies throws exception
- [x] **T208:** Update `ContributionPosting` to use currency-aware Money
- [ ] **T209:** Integration test: confirm existing contributions have `currency_code = 'GHS'` after migration

**Acceptance:** `make check` passes; all money operations explicitly specify currency; migration backfills existing rows.

---

### Concurrency Safety Verification (Decision #14)

- [ ] **T210:** Write concurrent integration test: two threads attempt to confirm same contribution from PENDING
- [ ] **T211:** Test setup: two separate `DataSource` connections, explicit transaction boundaries
- [ ] **T212:** Test assertion: exactly one thread succeeds state transition; second thread sees CONFIRMED and skips ledger posting
- [ ] **T213:** Verify ledger has exactly one posting set for the contribution (idempotency constraint enforced)
- [ ] **T214:** Document in `ContributionConfirmationService` or `backend/README.md`: "PostgreSQL READ COMMITTED + SELECT FOR UPDATE ensures single-writer; idempotency key is second layer"

**Acceptance:** Concurrent test passes; race condition documented as safe.

---

### Settlement Schema and Model (ADR 0012)

- [x] **T215:** Create `Settlement` entity: `id`, `provider`, `providerSettlementReference`, `settlementDate`, `grossAmount`, `fees`, `netAmount`, `status`, `receivedAt`, timestamps
- [x] **T216:** Create `SettlementItem` entity: `id`, `settlementId`, `contributionId` (nullable), `itemType`, `grossAmount`, `feeAmount`, `netAmount`, timestamps
- [x] **T217:** Create `SettlementRepository`: `save`, `findByProviderReference`, `findPendingBatches`, `findByContributionId`
- [x] **T218:** Property test: `Settlement` net breakdown check (net = gross - fees), matching DB constraint
- [x] **T219:** Unit test: settlement item with null `contributionId` is allowed (provider adjustments)
- [x] **T220:** Add `INV-SETTLE-001` test (disabled): payout cannot exceed settled balance (implementation with FEAT-002 payout service)
- [x] **T221:** Add `INV-SETTLE-004` test: duplicate settlement for same contribution raises alert and is rejected

**Acceptance:** Settlement entities and repo exist; schema constraints match validation logic.

---

### Suspense and Loss Allocation (ADR 0013)

- [x] **T222:** Update `LedgerAccount` enum: add `SUSPENSE`, `PLATFORM_LOSS`
- [x] **T223:** Update `LedgerEntryType` enum: add `SUSPENSE_HOLD`, `LOSS_ALLOCATION`
- [x] **T224:** Update `ContributionConfirmationService.confirm()`: check if goal accepts contributions
- [x] **T225:** If goal closed/frozen: set `requires_reconciliation = true`, post to SUSPENSE instead of goal pool, log warning
- [x] **T226:** Integration test: late success after goal closure creates SUSPENSE entry, not goal pool credit
- [x] **T227:** Add `INV-LEDGER-008` test: SUSPENSE entries have corresponding `requires_reconciliation` flag
- [x] **T228:** Query method: `ContributionRepository.findRequiringReconciliation()` for operator dashboard

**Acceptance:** Late payments to closed goals park in SUSPENSE; goal pool balance unaffected.

---

## Phase 2: Payout Module (FEAT-002 spec required first)

### Payout State Machine

- [ ] **T230:** Create `specs/domain/payout-state-machine.md` (similar structure to contribution state machine)
- [ ] **T231:** Define states: `REQUESTED`, `APPROVED`, `HOLD`, `READY`, `EXECUTING`, `COMPLETED`, `CANCELLED`, `FAILED`
- [ ] **T232:** Define transitions: request → approval (if needed) → hold → ready → execute → completed
- [ ] **T233:** Document goal freeze precedence: HOLD continues but READY→EXECUTING blocked if goal frozen
- [ ] **T234:** Implement `PayoutStateMachine` transition table
- [ ] **T235:** Create `Payout` entity with state, goal, amount, destination, hold window expiry, approvals

**Acceptance:** Payout state machine documented and implemented; transitions unit-tested.

---

### Settlement-Based Eligibility

- [ ] **T240:** Implement `SettlementService.settledBalance(goalId)`: goal-owned signed settled amount from ADR 0014, capped by the goal pool ledger balance
- [ ] **T241:** Payout validation: check `requestedAmount <= settledBalance`, reject with 422 if insufficient
- [ ] **T242:** Integration test: confirmed but unsettled contributions do not increase payout eligibility
- [ ] **T243:** Enable `INV-SETTLE-001` test: payout amount <= settled balance
- [ ] **T244:** Dashboard API: expose `settledBalance` and `pendingSettlement` separately from `raisedTotal`

**Acceptance:** Payouts blocked until settlement confirms cash receipt.

---

### Refund Timing Policy (ADR 0013)

- [ ] **T250:** `RefundService.requestRefund(contributionId)`: check if any payout executed for contribution's goal
- [ ] **T251:** Before payout: normal REVERSAL posting, contribution → REFUNDED
- [ ] **T252:** After payout: create `RefundRecoveryRequest` (manual workflow), log, alert operator
- [ ] **T253:** Operator UI (future FEAT-005): approve recovery, attempt beneficiary clawback, record outcome
- [ ] **T254:** If recovery fails: operator creates LOSS_ALLOCATION entry with approval and reason
- [ ] **T255:** Integration test: refund before payout decreases goal pool; refund after payout enters recovery workflow
- [ ] **T256:** Enable disabled `INV-LEDGER-003` test: goal pool never negative after refund handling

**Acceptance:** Pre-payout refunds automatic; post-payout refunds enter manual workflow with loss allocation.

---

## Phase 3: Operator Tooling (Can defer to FEAT-005)

### Reconciliation Dashboard

- [ ] **T260:** Operator endpoint: `GET /operator/reconciliation/pending` lists contributions with `requires_reconciliation = true`
- [ ] **T261:** Operator action: approve reclassification (SUSPENSE → goal pool) with reason
- [ ] **T262:** Operator action: issue refund from SUSPENSE
- [ ] **T263:** Operator action: record as misc income if unclaimed after timeout

### Settlement Ingestion

- [ ] **T270:** Manual CSV upload endpoint: parse Paystack settlement report format
- [ ] **T271:** Create settlement batch record with PENDING status
- [ ] **T272:** Match settlement items to contributions by provider_reference
- [ ] **T273:** Raise alert for unmatched items (create item with null contribution_id)
- [ ] **T274:** Operator marks settlement SETTLED after bank statement confirms receipt
- [ ] **T275:** (Future) Automated Paystack API polling for settlements

---

## Phase 4: ADR Status Updates (After implementation)

- [ ] **T280:** Update `docs/adr/0010-opaque-sessions.md` status from Proposed → Accepted (already implemented in FEAT-003)
- [ ] **T281:** Update `docs/adr/0009-sms-otp-provider.md` status when production SMS adapter lands
- [ ] **T282:** Review `specs/README.md` status table: mark settlement domain spec as Accepted when tests pass
- [ ] **T283:** Update `README.md` project status: note FEAT-002 implementation and remaining manual steps

---

## Definition of Done (per Constitution)

Each task's check must pass:
- [ ] Affected tests green
- [ ] `make check` (build, lint, typecheck, traceability) passes
- [ ] Criterion/invariant IDs referenced by `@Criterion` annotations
- [ ] New env vars added to `.env.example`
- [ ] Review findings recorded in `docs/reviews/FEAT-002.md`
- [ ] No disabled tests for completed criteria

**Next step:** Write `specs/features/002-payouts/spec.md` using FEAT-001/003 as templates, then create `plan.md` referencing these tasks.

## Review blockers (2026-11-08)

- [x] **T284:** Define goal-owned settled balance excluding `PLATFORM_FEE` and `requires_reconciliation`; specify signed reversals/chargebacks; amend settlement spec/ADR/T240.
- [x] **T285:** Enforce `INV-SETTLE-003` before `markSettled`; alert on mismatch; reject items in terminal batches; rewrite the test.
- [x] **T286:** Add direction/sign items, status-aware uniqueness and append-only protection; migration + tests.
- [x] **T287:** Obtain and record CODEOWNER review in `docs/reviews/FEAT-002.md` — signed off 2026-11-08.

## Hardening follow-ups (2026-11-08)

- [x] **T288:** V6 SUSPENSE guard; flag set before posting; FROZEN, deadline, normal, fee and duplicate-callback coverage.
- [x] **T291:** PENDING→SETTLED/FAILED state guard; terminal-batch item rejection; tests.
- [x] **T289:** Goal/contribution/quote/raised-total currency consistency and callback currency checks are enforced.
- [x] **T290:** Provider-line idempotency, after-completion alerts and parent guards are implemented and tested.
- [x] **T292:** Explicit goal-close-vs-confirm race test (two connections) and corrected concurrency documentation.
- [x] **T293:** ADR 0015 rejected in favour of V8 renaming financial columns to `*_minor_units`;
  settlement item types and deferred ADR 0007 settlement postings are documented.
- [x] **T294:** Currency checks, PostingSet currency guard, settlement validation and public suspense
  exclusion added; remaining API currency/backfill/clock hygiene is noted.
- [x] **T295:** Docs/index and the rolling-deploy note for the V5/V8 renames are recorded.

## Review follow-ups

- Outstanding: the FEAT-002 payout feature spec before Phase 2.
