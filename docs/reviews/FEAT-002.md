# Review: FEAT-002 Phase 1 foundation

- Date: 2026-11-08
- Range: `feat/003-hardening..working tree` (`feat/002-foundation`)
- Reviewer: fresh-context subagent (read-only); CODEOWNER review still required
- Verdict: blocked

## Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `specs/domain/settlement.md`, ADR 0012, T240 | Payout eligibility is defined as settlement net, which includes the platform fee; reversals/chargebacks have no sign convention. | INV-SETTLE-001, ADR 0007/0012 | T284: define goal-owned settled balance excluding `PLATFORM_FEE` and `requires_reconciliation`, with signed reversals; amend spec/ADR/T240. |
| Blocker | `SettlementRepository`, `SettlementInvariantsTest` | INV-SETTLE-003 is tautological; `markSettled` does not validate item totals or parent status. | INV-SETTLE-003 | T285: validate before settling, alert on mismatch, reject items in terminal batches; rewrite tests. |
| Blocker | `V5`, `SettlementItem`, `Money` | The item model cannot represent reversals/chargebacks/re-settlement: positive-only amounts, global `UNIQUE(contribution_id)`, no append-only guard. | INV-SETTLE-004, ADR 0012 | T286: direction/sign model, status-aware uniqueness, append-only trigger, migration/tests. |
| Blocker | `docs/reviews/` | No CODEOWNER review record for ledger/payment posting changes. | Constitution I.4 | T287: CODEOWNER review appended before the next slice. |
| Should-fix | `ContributionConfirmationService` | SUSPENSE flag and posting can diverge; coverage only CLOSED+FAILED. | INV-LEDGER-008 | T288. |
| Should-fix | goal/contribution paths | Currency is not compared between goal and contribution; hard-coded GHS remains; no supported-currency allowlist. | INV-LEDGER-007, ADR 0011 | T289. |
| Should-fix | `SettlementRepository` | No parent status/currency checks, orphan alerts, item idempotency, after-commit alerting, or duplicate-batch alert. | ADR 0012 | T290. |
| Should-fix | settlement lifecycle | PENDING→FAILED missing; direct SETTLED inserts allowed. | settlement.md | T291. |
| Should-fix | concurrency | Javadoc wording conflicts with Constitution IV.1; no explicit two-connection/goal-close race test. | Decision #14 | T292. |
| Should-fix | docs/schema | ADR 0011 `*_minor_units` drift; ADR 0007 vs 0012 settlement postings; CHARGEBACK undocumented. | Article II | T293. |
| Note | API/tests | Currency in public responses, backfill test, deterministic clocks, Money.ZERO, PostingSet currency check, ledger currency write proof, suspense public display. | Various | T294. |
| Note | docs | Update spec index, task checkboxes, T209 honesty, rolling-deploy rename risk. | Article II | T295. |

## Follow-ups

T284–T287 block the next slice; T288–T295 need owners in `docs/FEAT-002-BLOCKERS.md`. CODEOWNER review cannot be replaced by this AI review.

## Resolution (2026-11-08)

- **T284 resolved:** ADR 0014 defines directional settlement and goal-owned eligibility:
  `available = min(goalPool, max(0, signed goal_amount from SETTLED items))`, excluding fees and
  `requires_reconciliation`. `SettlementRepository.settledGoalAmount/availableForPayout` and
  `SettlementEligibilityTest` prove fee/suspense/reversal behavior.
- **T285 resolved:** `markSettled` validates signed item gross/fees/net against the batch, raises
  `SETTLEMENT_TOTAL_MISMATCH`, and refuses the transition. `SettlementInvariantsTest` covers a real
  mismatch and a matching batch.
- **T286 resolved:** settlement batches/items are directional with `goal_amount_minor_units`;
  items are append-only, only PENDING batches accept items, and contribution settlement is
  status-aware (retry after FAILED). Tests cover duplicate/rejection, append-only and terminal
  batch behavior.
- **T287 resolved:** CODEOWNER Ahmed Zubairu reviewed and signed off the Phase 1 ledger/payment
  changes on 2026-11-08.
- **T288–T295 remain** as should-fix/follow-up tasks in `docs/FEAT-002-BLOCKERS.md`.

The full backend suite and `make check` pass; trace is 56 defined / 54 active / 2 pending
(payout-only). V5 was verified on a clean Postgres and in Testcontainers; `make up` hit a local
Redis port conflict on this machine.

## CODEOWNER sign-off

- Reviewer: Ahmed Zubairu (CODEOWNER, `ledger/` and payment posting)
- Date: 2026-11-08
- Decision: **Approved** for the Phase 1 foundation. T288–T295 remain follow-ups; Phase 2 (payout
  module) still requires its feature spec and must not start from this approval alone.

## Hardening follow-up (2026-11-08)

- V6 adds a database guard: a `SUSPENSE`/`SUSPENSE_HOLD` entry is rejected unless its contribution
  is flagged `requires_reconciliation`; the confirmation service sets the flag before posting.
- SUSPENSE coverage now includes FROZEN goals, past deadlines, a normal open-goal confirmation,
  fee entries and duplicate callbacks.
- Settlement repository: parent-status/currency guards, orphan/unmatched alerts, duplicate-batch
  alerts and metrics; settlement state guards and terminal-batch item rejection are tested.
- Concurrency Javadoc corrected (DB constraints are the correctness guarantee; the row lock
  serializes).
- T289/T292 completed: goal/contribution/callback currencies are compared; a two-connection close-vs-confirm race test asserts exactly one ledger outcome.
- T294/T295 completed; T293 (ADR 0015 acceptance) and T290 remain open.
