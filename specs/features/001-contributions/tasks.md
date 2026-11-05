# Tasks: Guest contribution

Order is test-first: for each criterion, the failing test comes before the code. `[P]` marks tasks that can run in parallel because they touch different files. Local progress is ticked when the task's tests are green on `main`; release still requires a pull request.

## Setup

- [x] T001 Initialize git and commit the scaffold (`b14bb99`)
- [x] T002 Generate and commit the Gradle wrapper; install/select JDK 21; run `make check` (`cd21ecc`)
- [x] T003 Fix `tools/check_traceability.py` so the Javadoc example in `support/Criterion.java` is not counted as a test (`cd21ecc`)
- [x] T004 Update `specs/api/openapi.yaml`: Paystack `x-paystack-signature` header and callback payload, keeping `POST /webhooks/momo`
- [x] T005 Add configuration keys: `PAYSTACK_SECRET_KEY`, `PAYSTACK_BASE_URL`, `PAYER_PHONE_KEY`, `PLATFORM_FEE_BPS=150`, `PAYSTACK_FEE_BPS=195`, `CONTRIBUTION_TIMEOUT=PT5M`; no secrets in the repository
- [ ] T006 Build the in-process `FakePaystackServer` with success, declined, timeout, duplicate, late, bad signature, wrong amount, request rejected and status lookup scenarios
- [ ] T007 Add the three missing failure-table criteria (prompt request failure, unknown provider reference, amount mismatch) before implementing those paths
- [ ] T008 Update the implementation plan and spec with any decision changed during setup

## Slice 1 (P1): Fees and amount rules

Criteria: AC-CONTRIB-003, AC-CONTRIB-014, AC-CONTRIB-017

- [x] T010 Write the failing `FeeCalculatorTest` for AC-CONTRIB-017: 1.5% rounded up, charged on top, goal unchanged (`3458785`)
- [x] T011 Implement `FeeCalculator` platform-fee calculation until T010 is green
- [x] T012 Write the failing property test for AC-CONTRIB-014: total equals amount plus both fees
- [x] T013 Implement processing-fee and total gross-up; keep integer arithmetic
- [x] T014 Write the failing `GoalRulesTest` for AC-CONTRIB-003: open-goal minimum is 1,000 pesewas
- [x] T015 Implement the goal amount policy until T014 is green
- [x] T016 Refactor the fee and policy code; keep the suite green

## Slice 2 (P1): Create, prompt and public goal

Criteria: AC-CONTRIB-001, AC-CONTRIB-002, AC-CONTRIB-011, AC-CONTRIB-012, AC-CONTRIB-015

- [x] T020 Write `V1__goal_and_contribution.sql`: goals, contributions, contribution_events, indexes, unique idempotency key and provider reference
- [x] T021 Write the failing integration test for AC-CONTRIB-001 against the fake Paystack server
- [x] T022 Implement the public goal read and contribution creation until T021 is green
- [ ] T023 Write the failing integration test for AC-CONTRIB-002: fixed goal rejects a different amount with 422
- [ ] T024 Implement the fixed-amount rule until T023 is green
- [ ] T025 Write the failing integration test for AC-CONTRIB-012: closed, frozen and past-deadline goals return 409
- [ ] T026 Implement goal acceptance checks until T025 is green
- [ ] T027 Write the failing integration test for AC-CONTRIB-015: same Idempotency-Key returns the original and sends one prompt
- [ ] T028 Implement the database-backed idempotency path until T027 is green
- [ ] T029 Write the failing contract test for AC-CONTRIB-011: no phone number in any public response
- [ ] T030 Make the public DTOs phone-free until T029 is green
- [ ] T031 Refactor the create path; keep the suite green

## Slice 3 (P1): Callbacks, state machine and ledger

Criteria: AC-CONTRIB-004, AC-CONTRIB-005, AC-CONTRIB-006, AC-CONTRIB-007, AC-CONTRIB-016, INV-LEDGER-001, INV-LEDGER-002, INV-LEDGER-004, INV-LEDGER-006, INV-STATE-001, INV-STATE-002, INV-STATE-003

- [ ] T040 Write `V2__ledger.sql`: ledger_entries, posting-set balance trigger, append-only triggers
- [ ] T041 Write the failing `ContributionStateMachineTest` for INV-STATE-001 and INV-STATE-003
- [ ] T042 Implement the transition table and history append until T041 is green
- [ ] T043 Add duplicate, declined and bad-signature scenarios to the fake server
- [ ] T044 Write the failing integration test for AC-CONTRIB-007: invalid signature returns 401 and changes nothing
- [ ] T045 Implement Paystack signature verification until T044 is green
- [ ] T046 Write the failing integration test for AC-CONTRIB-006: FAILED callback leaves the ledger empty
- [ ] T047 Implement the FAILED path until T046 is green
- [ ] T048 Write the failing integration test for AC-CONTRIB-005: SUCCESS confirms and posts balanced entries in one transaction
- [ ] T049 Implement the posting engine and confirmation transaction until T048 is green
- [ ] T050 Write the failing integration test for AC-CONTRIB-004: duplicate SUCCESS posts once
- [ ] T051 Enforce the unique posting set until T050 is green
- [ ] T052 Write the failing integration test for AC-CONTRIB-016: late SUCCESS after EXPIRED/FAILED confirms once and alerts
- [ ] T053 Implement the late-success transitions and alert until T052 is green
- [ ] T054 Write the failing `LedgerPostingIT` for INV-LEDGER-001, INV-LEDGER-002, INV-LEDGER-004 and INV-LEDGER-006
- [ ] T055 Implement the remaining ledger guards and derived-balance queries until T054 is green
- [ ] T056 Write the failing integration test for INV-STATE-002: ledger entries exist only for CONFIRMED contributions
- [ ] T057 Implement the invariant check until T056 is green
- [ ] T058 Refactor the callback and posting path; keep the suite green

## Slice 4 (P1): Timeout, status and receipt

Criteria: AC-CONTRIB-008, AC-CONTRIB-013

- [ ] T060 Write the failing integration test for AC-CONTRIB-008 with an injected clock and fake status lookup
- [ ] T061 Implement the timeout job with `FOR UPDATE SKIP LOCKED` until T060 is green
- [ ] T062 Write the failing contract test for AC-CONTRIB-013: no receipt while PENDING, full receipt once CONFIRMED
- [ ] T063 Implement the status and receipt response until T062 is green
- [ ] T064 Refactor; keep the suite green

## Slice 5 (P2): Anonymous feed and retry

Criteria: AC-CONTRIB-009, AC-CONTRIB-010

- [ ] T070 Write the failing integration test for AC-CONTRIB-009: retry after FAILED/EXPIRED creates a new contribution
- [ ] T071 Implement retry until T070 is green
- [ ] T072 Write the failing contract test for AC-CONTRIB-010: anonymous feed hides the name
- [ ] T073 Implement the feed projection until T072 is green
- [ ] T074 Refactor; keep the suite green

## Hardening

- [ ] T090 Failure-path tests for every row in the spec's failure table, including T007's new criteria
- [ ] T091 Structured logs, metrics and alerts listed in the spec
- [ ] T092 Rate limiting per IP and per MoMo number
- [ ] T093 Run `make check`; remove every `@Disabled` marker for FEAT-001
- [ ] T094 Update the spec status to Implemented
- [ ] T095 Scaffold `web/` and implement the public goal, checkout and receipt pages after the backend slice is green
- [ ] T096 Run the manual Paystack sandbox suite before the first live payment
