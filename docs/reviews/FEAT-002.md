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
- T290/T294/T295 completed; T293 resolved by rejecting ADR 0015 and applying V8 column renames.

## Spec review (2026-11-09)

The FEAT-002 payout spec was independently reviewed before implementation. The review found four
blockers and several highs; all were accepted and the spec/state machine/plan were corrected:

- **Reservations:** active payouts (REQUESTED, APPROVED, HOLD, READY, EXECUTING) reserve X under the
  goal lock; available subtracts reservations; completion re-checks under the same lock
  (AC-PAYOUT-013, INV-PAYOUT-007).
- **Article V.2:** public payout visibility, contributor reports and sent notifications were added
  as story/rule/criteria (AC-PAYOUT-014, INV-PAYOUT-008).
- **APPROVED cancellation:** the transition was added; APPROVED was already in the cancellable
  list.
- **Fee mismatch:** re-quote before the provider call; `READY -> FAILED` with no transfer
  (AC-PAYOUT-015).
- **Mandatory hold:** a hold below one minute is refused with `HOLD_REQUIRED` (AC-PAYOUT-016);
  zero-hold legacy goals must set payout settings first.
- **Approval resend/expiry:** resend invalidates prior token/code; expiry cancels the payout
  (AC-PAYOUT-017).
- **Idempotency conflict:** same key with a different body returns 409 (AC-PAYOUT-018).
- **Worker:** a scheduled CAS worker owns READY→EXECUTING; no client execute endpoint; cancel and
  execute have one winner (AC-PAYOUT-019).
- **Naming/ledger:** the live `secondApproverThresholdPesewas` field is used; the ledger sentence now
  states gross X, net X−G and fee G; zero fee omits the fee line.
- **Review/flag/trace:** fresh CODEOWNER review of the payout posting is required; the feature flag
  returns 404 only; T230 is closed and the Playwright spec carries AC-PAYOUT-012.

The corrections were re-reviewed and merged on 2026-11-09 (PR #10); the spec is **Ready** and the domain spec is **Accepted**. No payout code has started.

## Approver-settings slice review (2026-11-09)

Independent read-only review of the provider-independent T303/T304 slice (goal approver fields,
`PATCH /organizer/goals/{id}/payout-settings`, public approver name, V9 schema, web creation
inputs). No merge blockers were found. Findings and resolutions:

- **Settings-lock TOCTOU (High, partially fixed; T319):** the PATCH now locks the goal row before
  the check and update, and a V9 trigger rejects any settings update after a payout exists. A row
  lock on `goals` does **not** block an insert into `payouts`, so the payout writer must take the same
  goal lock before inserting. T319 owns that writer lock and the PATCH-vs-payout concurrency test;
  the trigger covers the sequential/late-settings case.
- **AC-006 wording / traceability (High, fixed by split):** AC-006 is now settings-only. The
  request-time gate is **AC-PAYOUT-020** with a pending test; T319 carries the implementation. The
  trace is honest: AC-006 is active for settings, AC-020 remains pending.
- **Nullable SQL parameters (High, fixed):** the settings update casts nullable approver/hold
  parameters and tests hold-only updates on an OFF goal and on a goal that already has an approver.
- **Web approver setup (should-fix, fixed):** the goal wizard now collects the approver name and
  MoMo number for non-OFF rules and includes them in the idempotent create payload; a test covers
  missing and valid values. The settings form for legacy goals remains T351.
- **OpenAPI hold/threshold contract (should-fix, fixed):** the schema permits 0 so the server can
  return the documented 422 `HOLD_REQUIRED`; `PayoutSettings` has `minProperties: 1`; the threshold
  description now says it compares to the payout debit X, not a contribution amount.
- **Approval cardinality (Medium, fixed):** a partial unique index keeps at most one unconsumed
  challenge per payout; resend must consume the previous challenge.
- **Notifications per contributor (Medium, fixed in schema; sender follow-up in T343):**
  `payout_notifications` stores `payer_phone_hash` and is unique per contributor per payout. The
  sender must aggregate by `payer_phone_hash`, insert one row per contributor and treat a conflict
  as already sent.
- **CODEOWNER review:** this slice contains no ledger/payment-provider code. A fresh CODEOWNER
  review remains mandatory before the payout posting/execution slice (T365).

Verification: `make check` passed; `GoalPayoutSettingsIT` passed; trace 83 defined / 56 active /
27 pending / 0 missing; web tests, lint and build passed.

## Payout-request slice review (2026-11-09)

Independent read-only review of the Slice 1 payout quote/request implementation. Verdict on the
first pass was **blocked** for money/approver correctness; the blockers and should-fix findings were
addressed as follows.

- **B1 settled cap (blocker, fixed):** available is now
  `max(0, min(pool, max(0, settled − completed payout total)) − active reservations)`.
  Added tests for partial settlement plus an active reservation and for a completed payout plus
  unsettled funds; the spec, state-machine term and plan now use the same formula.
- **B2 ABOVE_THRESHOLD gate (blocker, fixed):** the approver gate now applies to every non-OFF
  rule before the amount trigger; ABOVE_THRESHOLD below its threshold returns 422
  `APPROVER_REQUIRED`. AC-020's test uses that case.
- **S1 idempotency scope (fixed by spec amendment):** the request is amount-only and re-quotes the
  fee; AC-018 and the failure table now say “different amount”, matching OpenAPI. Fee changes are
  the pre-transfer AC-015 path, not an idempotency conflict.
- **S2 quote endpoint (fixed):** `X == fee` now returns 422 `AMOUNT_TOO_LOW`; a quote IT covers
  fee/net/available, the at-fee case and ownership 404.
- **S3 traceability (fixed by split):** request-phase rows **AC-PAYOUT-021** (fee/net row) and
  **AC-PAYOUT-022** (reservations) are active. The full AC-003 (zero-fee ledger line), AC-009 (no
  second posting) and AC-013 (cancel/failure release) remain pending; T312/T314/T315/T316/T319
  remain open. Trace: 86 defined / 63 active / 23 pending / 0 missing.
- **S4 V10 enforcement (fixed):** `ledger_entries` now checks PAYOUT/PAYOUT_FEE rows have a
  `payout_id` and has one-line-per-account uniqueness; `LedgerRepository.postPayout` writes the
  link and the completed-payout test uses the real posting method.
- **S5 fee fallback (fixed/limited):** the configured fee provider is opt-in
  (`collections.payout.fee-provider=configured`) with no default fee, and `PayoutService` only
  exists when `collections.payout.enabled=true`; enabling without a provider fails startup. The
  provider call still occurs under the goal lock; T302 must move the real network quote before the
  lock.
- **S6 state-machine audit (fixed):** every request inserts REQUESTED and appends that event; auto
  approval uses the state-machine APPROVE transition, sets `approved_at` and appends APPROVED.
  AC-001 asserts both events.
- **S7 observability (fixed):** phone-free `payout.requests` and
  `payout.rejected{reason}` counters and log entries cover request failure paths; tests assert
  success and `approver_required`.

Process: this AI review does not replace the fresh CODEOWNER review required for the payout/ledger
changes (T365). No real provider transfer or ledger posting path is enabled in this slice.

Re-review after the fixes: the two blockers are resolved and no new blockers were found. Follow-up
fixes from that re-review: the plan sequence now uses the corrected formula, `SettlementRepository`
exposes a raw `rawSettledCap` instead of a misleading full `availableForPayout`, and the task/test
names distinguish request-phase coverage (AC-021/022) from the still-pending full AC-003/009/013 and
the PATCH-vs-payout race (T319).

## Approval/hold slice review (2026-11-10)

First review found one blocker: exhausting the five approval attempts consumed the challenge but left
the payout `REQUESTED`, permanently reserving its amount with no release path. Fixed by adding the
`REQUESTED -> CANCELLED (APPROVAL_EXHAUSTED)` transition to the state machine and spec, cancelling
the payout in the same transaction as the attempt-cap consume, and asserting cancellation,
reservation release, challenge consumption and zero ledger rows in `PayoutApprovalIT`. The full
transition matrix covers the new event.

Also fixed from the review: approval verify now uses the state-machine APPROVE transition; decline,
expiry and hold expiry use DECLINE/APPROVAL_EXPIRED/HOLD_EXPIRED transitions; `markApproved` is
checked; code-expiry and successful-code reuse are tested; approval values are asserted hashed at
rest (INV-PAYOUT-004); OpenAPI documents 503 on payout request/resend; SMS failures alert, log and
normalize to `SMS_UNAVAILABLE`.

Re-review verdict: blocker resolved, no new blockers. Remaining should-fix items are tracked:
T326 approval metrics/abuse tests, T327 per-phone/IP approval rate limiting, T328 organizer
notification on expiry/exhaustion/decline, and AC-007 worker freeze/execution coverage. Trace:
88 defined / 70 active / 18 pending / 0 missing; `make check` green.

Approval follow-up review (2026-11-10): the SMS send now runs after the request transaction commits and
outside the goal row lock (`PayoutController` -> `sendForPayout`); the challenge is inserted and
committed before sending, a failed send consumes that challenge, and the payout stays REQUESTED so
the same idempotency key can retry. The retry path is tested. The logging SMS provider no longer
logs message bodies/codes. Organizer notification on expiry/exhaustion/decline remains explicitly
owned by T328; rate limiting by T327. Setup rules now forbid external I/O under row locks, logging
one-time codes, and unowned transition side effects.

Approval hardening follow-up (2026-11-10): T327 added a per-IP/per-approver-phone rate limiter to the public
approval GET/verify/resend/decline endpoints, with a unit test and 429 responses documented in
OpenAPI. T328 now sends an after-commit SMS to the organizer on decline/expiry/exhaustion; failures
alert and log without changing the payout. T326 approval metrics/logging is complete. The organizer
SMS is sent after commit, so it does not run while a state-changing transaction or row lock is held.
Trace remains 88 defined / 70 active / 18 pending / 0 missing; `make check` green.

Approval hardening re-review (2026-11-10): T327 now checks the IP bucket before any token lookup,
removes the invalid-token fallback bucket, keys the second bucket by the resolved approver phone
across goals, emits a `payout.approval.rate_limited` metric and redacted log, and uses distinct
429 copy that does not imply the challenge was burned. Unit tests cover IP-only, approver-only and
one-minute window reset; a separate HTTP IT exercises all four approval routes with rate limiting
enabled. T328 tests now assert the specific organizer messages for decline, exhaustion and expiry,
and a failed organizer SMS leaves the payout cancelled with an SMS_FAILURE alert. T326 is complete.
The test harness caps Hikari pools at two connections so the growing Spring context set no longer
exhausts Postgres. `make check` green; trace 88 defined / 70 active / 18 pending / 0 missing.

Contributor-visibility review (2026-11-10): no blockers found. Fixed the review's should-fix items:
notifications normalize the decrypted payer number to E.164; the notification test now covers two
distinct payers, duplicate contributions, pending/reconciliation exclusions and SMS failure with an
alert and unchanged payout; the public-view test asserts amount/fee/hold/completed, approver/payer
phone absence and cross-goal isolation; hidden payouts return 404 for reports; the report SQL casts
the nullable contribution UUID; payout ordering is deterministic; the web renders hold expiry only
for HOLD and tolerates an older backend without `payouts`; report rejection counters were added.
The completion worker caller is explicitly assigned to T341 (after-commit `notifyCompleted`), with
AC-014's E2E UI/report action still pending under T342. `make check` green; trace 90 defined / 72
active / 18 pending / 0 missing; web tests/lint/build green.

Contributor-visibility follow-up (2026-11-10): the public read/report dependency cycle was removed by
introducing a payout-owned `PayoutReportRateLimiter` with its own key and 429 detail; reports no
longer touch contribution payment limits. Notifications now delete the claimed row on SMS failure so
the same contributor can be retried, and the failure test proves alert/no-payout-change followed by a
successful retry. The payout list was moved out of Recent contributions; the report UI remains T342
for AC-014 E2E. Added cross-goal report 404 and wrong-contribution 422 tests, amount/fee/hold/
completed assertions, deterministic payout ordering, and a nullable contribution cast. `make check`
green; trace 90 defined / 72 active / 18 pending / 0 missing; web tests/lint/build green.

Payout-execution review (2026-11-10): the first pass was blocked because completion posted the
ledger without taking the goal row lock, allowing a request to see a released reservation with a
stale completed total. Fixed by locking the goal in `PayoutCompletionService` before posting and
testing exact payout ledger lines; the request/approval/hold/worker writes also use the state
machine. Completion notifications are asserted through the after-commit path; AC-009 is back to a
pending test because the worker retry scan is not the request-idempotency criterion; the execution
test cleans stale READY rows. Follow-up fixes: fee mismatch and frozen goals now increment metrics and publish
operator alerts; provider exceptions and explicit UNKNOWN results append PROVIDER_UNKNOWN events;
provider references are persisted before completion, and completion failures leave the payout
EXECUTING with a stuck alert. The execution scan no longer locks payout rows, excludes frozen goals,
and takes the goal lock first, removing the cancel deadlock and frozen starvation. Ledger tests now
assert exact line accounts/directions/amounts and provider failure codes. Remaining should-fix:
T302 (fee quote under the claim lock), T316 (AC-009 pending) and T332/T334 (cancel/execute race and
cancellation release). `make check` green; trace 90 defined / 78 active / 12 pending / 0 missing.

Cancellation/race slice (2026-11-10): added the organizer cancel endpoint with goal-row locking,
allowed-state enforcement, approval-challenge consumption and reservation release; cancellation
never posts ledger. A cancel-vs-execute race test proves exactly one winner, and execution failure
release remains covered. AC-008/AC-013/AC-019 are now active; trace 90 defined / 81 active / 9
pending / 0 missing; `make check` green. Remaining: AC-009, AC-011 ownership and the real provider
adapter T302.

Cancellation, ownership and idempotency completion (2026-11-10): AC-008/AC-013/AC-019 are active,
AC-009 now covers a completed payout retry with no second posting, and AC-011 covers foreign
organizer list/quote/request/cancel access. Existing request, notification, hold and settings tests
now carry the matching payout and settlement invariants, so trace is **90 defined / 90 active / 0
pending / 0 missing**. Remaining: T302 real Paystack adapter and fee-quote/lock follow-up, T344
stuck-EXECUTING verification job, T361 abuse tests, and the mandatory fresh CODEOWNER review T365
before real payout execution.

External review follow-up (2026-11-10): cancellation now calls the state-machine CANCEL transition
(bad states map to PAYOUT_NOT_CANCELLABLE); the execution service persists provider references
before completion, routes provider exceptions and explicit UNKNOWN results through the same
PROVIDER_UNKNOWN event/alert path, and alerts on completion persistence failure; frozen-goal
blocking is reported once per worker scan rather than once per claim; the ownership IT now also
POSTs a payout on the foreign goal. Tests cover provider exception and explicit-unknown alerting,
provider-reference persistence and the foreign request 404. `make check` green; trace 90/90/0.
Stuck-execution verification (2026-11-11): T344 adds **AC-PAYOUT-027**. `PayoutVerificationService`
sweeps payouts still `EXECUTING` past `collections.payout.stuck-after` (default `PT10M`) and records
one append-only `VERIFICATION_REQUIRED` event, a `PAYOUT_EXECUTION_STUCK` alert and a
`payout.execution.stuck` metric per payout. It never calls the provider and never posts ledger, so
the payout stays `EXECUTING` for operator verification; a failed alert is counted by
`payout.execution.stuck_alert_failed` rather than retried forever. The partial unique index on
`(payout_id) WHERE event = 'VERIFICATION_REQUIRED'` makes the flag once-only across instances, and
the repository locks the payout row before inserting so a completion cannot land between the scan
and the flag. The scan excludes already-flagged rows and orders by `executing_at, id`, so an outage
with more than 100 stuck payouts drains over subsequent scans instead of starving rows beyond the
first batch. The independent review's blocker was exactly that starvation; the regression test now
inserts 101 stale rows and proves all 101 are flagged, a concurrent-scan test proves one event and
one alert, and a terminal-payout test proves completed rows with an old `executing_at` are not
flagged. The state machine now declares `VERIFICATION_REQUIRED` (`EXECUTING → EXECUTING`), the domain
table and plan migration/test rows are updated, and V11 adds the partial indexes. `ApiIntegrationTest`
now also disables Spring Modulith Moments: Moments enables `@EnableScheduling` independently of
`collections.scheduling.enabled`, which let background jobs race this test. Deferred nits are owned
by T366 (backlog gauge and a minimum-threshold check). Remaining before real payouts: T302, T361 and
the fresh CODEOWNER review T365. `make check` green; trace 91 defined / 79 active / 12 pending /
0 missing.

Final FEAT-002 merge and reconciliation (2026-11-12): the cancellation, ownership and idempotency
slice (PR #21) merged after bringing `main` into the branch. The review-log conflict kept both
records, and the stuck-sweep tests were adapted to the execution-time `PAYOUT_EXECUTION_STUCK`
alert by asserting deltas rather than absolute counts; the enlarged IT class also got unique OTP
phone numbers and a higher OTP limit. Trace is **91 defined / 91 active / 0 pending / 0 missing**
and `make check` is green. The task list was reconciled with the delivered setup, request,
execution, web and hardening work. Remaining: T301/T302 (Paystack transfer sandbox and real
adapter), T319 (PATCH-vs-payout race test), T361 (report-spam abuse test), T362 (payout runbook),
T365 (fresh CODEOWNER review) and T366 (backlog gauge/threshold validation).

Abuse and privacy coverage (2026-11-12): T361 adds `PayoutReportRateLimitIT`, which proves the
public report endpoint accepts one report per IP per minute and returns 429 `RATE_LIMITED` (with the
`payout.report.rate_limited` metric) on the next attempt, and phone-free assertions over payout
events, alert details and meter tags for execution, approval and notification paths. Approval
pumping and token stuffing remain covered by `PayoutApprovalRateLimitIT` (all public routes are
rate-limited before token lookup) and `PayoutApprovalIT` (wrong/expired/reused/over-attempted
codes); cross-goal reports and foreign organizers remain covered by `PayoutContributorIT` and
`PayoutCancelIT`. Trace stays 91 defined / 91 active / 0 pending / 0 missing; `make check` green.

Settings/request race coverage (2026-11-12): T319 blocks the fee quote inside the payout request
on a goal that already has an approver and a 60-minute hold, then PATCHes the hold to 45. With the
payout-side goal lock the PATCH waits and returns 409 `PAYOUT_SETTINGS_LOCKED` with the hold still
60; removing `lockByIdAndCreator` from `PayoutService.request` makes the test fail because the PATCH
commits 200 during the blocked quote. AC-PAYOUT-020 remains covered by the existing 422 test.
Trace stays 91/91/0; `make check` green.

Backlog gauge and threshold validation (2026-11-12): T366 adds the
`payout.execution.stuck.current` gauge, which counts EXECUTING payouts older than the stuck
threshold at scrape time (including already-flagged rows, so a lost alert is visible). Startup now
refuses a `collections.payout.stuck-after` that is zero, negative, or not greater than
`collections.payout.provider-timeout` (defaults `PT10M` and `PT5S`), preventing an alert storm while
a transfer may still be in flight. The gauge assertions were red before registration, and unit tests
cover the threshold rule. Trace stays 91/91/0; `make check` green.

Payout configuration (2026-11-13): T306 makes the payout flags, configured fee fallback, approval
token/code TTLs, maximum attempts, resend delay and minimum hold explicit in `application.yml`
(`PAYOUT_*` environment overrides) and wires them into `PayoutApprovalService`, `PayoutService`,
`PayoutHoldService` and `OrganizerGoalService`. The approval TTL and attempt count are no longer
hard-coded. The configured fee provider has **no default amount**: selecting it without
`PAYOUT_TRANSFER_FEE_MINOR_UNITS` still fails startup, and `ConfiguredPayoutFeeProviderTest` covers
both cases. The minimum hold must be at least 1 in all three constructors; `PayoutConfigIT` proves a
stored hold below the configured minimum is rejected on request (no payout row), and
`PayoutHoldServiceTest` covers the hold start. The spec, failure table, plan and OpenAPI wording now
describe the configured minimum rather than a fixed one-minute/zero-hold rule. Trace stays 91/91/0;
`make check` green.

Paystack payout adapter (2026-11-13): T302 adds `PaystackPayoutTransferProvider` and
`PaystackPayoutFeeProvider`. The transfer adapter creates the MoMo recipient from the encrypted goal
destination, transfers the beneficiary net with `tgthr-payout-<payout id>` as the reference, maps
`success`/`failed`/`otp`/`pending` and resolves duplicate references through
`GET /transfer/verify`. 4xx responses are terminal, 5xx/timeouts are UNKNOWN. The fee provider uses
the documented GHS 1 MoMo fee (configurable). `FakePaystackServer` now serves recipient, transfer and
verify endpoints; unit tests cover success, failure, OTP, 5xx, amount/currency mismatch, transient
4xx and the duplicate-reference path. Stored E.164 destinations are converted to the local MoMo form,
and a success is only accepted when the returned amount equals the beneficiary net and the currency
is GHS. The T301 spike is recorded in `docs/runbooks/paystack-transfer-spike.md`; live sign-off is
blocked because the test business is still a starter and cannot initiate third-party payouts. No
automated test calls Paystack. Trace stays 91/91/0; `make check` green.

Payout operations runbook (2026-11-13): T362 adds `docs/runbooks/payout-operations.md` covering the
configuration flags, alert/metric names, fee-mismatch evidence and actions, stuck-EXECUTING
verification via Paystack with the no-re-execution rule, contributor/approval/organizer notification
retry semantics, public reports, frozen goals, phone-decryption handling and the transfer go-live
checklist. It records the known gaps: no operator completion/retry API (FEAT-005), no transfers OTP
finalization, the starter-business block, and the fresh CODEOWNER review T365. Docs only; trace stays
91/91/0.

CODEOWNER review plan (2026-11-13): T309 adds `docs/reviews/FEAT-002-CODEOWNER-REVIEW-PLAN.md`, a
bounded plan for T365 covering the payout posting and contributor notification paths: prerequisites
and evidence, exact file scope, money/state/notification/concurrency checklists, failure-path
evidence, the review procedure and explicit `ledger/`/`payments/` sign-off. It records the live
Paystack blocker and the FEAT-005 operator-tooling gap. This is an input to T365, not the review
itself. Docs only; trace stays 91/91/0.
