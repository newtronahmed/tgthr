# Reviews: FEAT-001 Guest contribution

Append one section per review using `specs/_templates/review.md`. Do not rewrite earlier sections; add a follow-up section when blockers are resolved.

## 2026-11-06 — slices 1–3 (fees, schema, state machine, webhooks, postings)

- Range: `b14bb99..HEAD`
- Reviewer: three fresh-context read-only subagents (money/ledger, payments/security, API contract). The first spec/test axis attempt failed twice with no output; its contract questions were covered by the third reviewer.
- Verdict: **blocked** on one finding; pass with follow-ups otherwise.

### Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `ContributionService.java:67-99`, `ContributionStateMachine.java` | A valid SUCCESS callback arriving while the contribution is still INITIATED is an illegal transition: acknowledged 204, no posting, no alert. Paystack stops retrying, and a confirmed payment can be lost (INITIATED is also never swept). | Article I.5; AC-CONTRIB-004; failure table "callback before PENDING commit" | **Fixed** with AC-CONTRIB-018 and the INITIATED → CONFIRMED transition (T059). |
| Blocker | `ContributionController.java:25` | OpenAPI requires `Idempotency-Key` length 8–64; the controller accepted any value. Missing header and malformed JSON fell through to Spring's default 400, not the Problem contract. | Article II.4; OpenAPI Problem | **Fixed**: `@Size` validation + Problem handlers; tests added. |
| Should-fix | `ContributionService.java:79-93` | Unknown provider reference and amount mismatch only WARN and return 204; no alert or reconciliation, so a confirmed payment can be discarded silently. | Article I.5, IX.2; T007 | T007 (criteria) + T091 (alerts). |
| Should-fix | `ContributionConfirmationService.java:33-36` | Late-success alert is an unstructured log emitted before the ledger post commits; no correlation ID or metric; illegal-transition log omits the contribution id. | Article IX.1–2; AC-CONTRIB-016 | T091. |
| Should-fix | `PaystackHttpClient.java` / `PaystackConfiguration.java` | No connect/read timeouts and no logging/metering of provider failures; a hung provider can hold request threads. | NFR p95; Article IX.1 | T097. |
| Should-fix | `PaymentResult.java`, `PaystackWebhookMapper.java` | Callback currency is ignored (a non-GHS success could post GHS entries); success is derived from `event` only and any other event becomes FAILED. | Article I.3; AC-CONTRIB-005/006 | T097 (validate currency + whitelist events). |
| Should-fix | `ContributionRepository.java` | `confirmed_at` is never written, so the AC-013 receipt cannot show the confirmation time. | AC-CONTRIB-013 | T063. |
| Should-fix | `ContributionService.java`, `ContributionRepository.java`, `PayerPhoneCipher.java` | `Instant.now()`, `UUID.randomUUID()` and static `SecureRandom` are used directly instead of injected. | Article III.4 | T060 (Clock) + T097 (ID/randomness seams). |
| Should-fix | `ContributionProblemAdvice.java` | A huge `amountPesewas` overflows `Math.multiplyExact` and returns 500; malformed webhook JSON returns 500. No 4xx mapping or metering. | Article IX.1; AC-CONTRIB-003 | T090. |
| Should-fix | `ContributionController.java`, `application.yml` | No rate limiting per IP or MoMo number. | Spec security section | T092. |
| Note | `ContributionService.java`, `Goal.java` | Goal acceptance is a read outside a transaction; a concurrent close/freeze can race the insert. | SPEC rule 4 | T090 (concurrency test + conditional insert). |
| Note | `ContributionRepository.java`, `ContributionApiIT.java` | The create-time duplicate-key race and concurrent confirmation paths are only tested sequentially. | AC-CONTRIB-015/004; Article IV.1 | T090. |
| Note | `V2__ledger.sql` | Append-only triggers do not block TRUNCATE; production grants are not in the repo. | INV-LEDGER-002; ADR 0002 | T090. |
| Note | `V1__goal_and_contribution.sql`, `GoalRules.java` | The stored minimum is only checked > 0; a goal below 1,000 pesewas would accept sub-GHS-10 gifts. | SPEC rule 2; AC-CONTRIB-003 | **Fixed**: schema floor `>= 1000`. |
| Note | `ContributionWebhookController.java`, `PaystackSignature.java` | Signature is computed over a decoded/re-encoded String rather than raw bytes; works for UTF-8 JSON but is not byte-exact. No replay window (idempotency is by reference). | ADR 0006; spec security | T097 / spec decision. |
| Note | `application.yml`, `CreateContributionRequest.java` | Committed dev DB password default; phone included in record `toString`; no key-rotation task. | Article VIII | T092/T091. |
| Note | `ContributionStateMachineTest.java` | INV-STATE-003 is verified only through transitions; history append-only is not exercised for every legal transition. | INV-STATE-003; Article III.2 | T093. |
| Note | `FakePaystackServer.java` | The fake never checks the Authorization header and its timeout scenario accepts after 2 s, so it cannot prove a client timeout. | Article III.2 | T006/T097. |
| Note | `ContributionController.java` | Idempotency keys are global, not scoped by goal; a cross-goal key returns the other goal's contribution. Product decision. | AC-CONTRIB-015 | Decision needed before launch. |

### Follow-ups

- Blocker fixes landed with AC-CONTRIB-018/T059 and contract validation; re-run recorded below.
- Should-fix owners: T007, T060, T063, T090, T091, T092, T097. T007, T060 and T063 were closed on 2026-11-06 (failure criteria, clock/timeout job, `confirmed_at` + receipt).
- Invariant INV-LEDGER-003 stays pending with FEAT-002 (payout scope).

## 2026-11-06 — blocker fix verification

- Reviewer: implementer (self-check) plus the original findings.
- Verdict: **pass with follow-ups**.

Resolved:

- **Early SUCCESS:** the domain spec now allows `INITIATED + PAYMENT_SUCCEEDED → CONFIRMED`; AC-CONTRIB-018 was added with an integration test that proves 204, CONFIRMED, four balanced entries and a single posting set.
- **Idempotency-Key contract:** the header is required to be 8–64 characters; missing headers and malformed bodies return `422 INVALID_REQUEST` in the Problem shape, with tests.
- **Minimum floor:** `goals.minimum_amount_pesewas` is now `>= 1000`.
- **Traceability:** illegal transitions log the contribution id.

Open follow-ups: T007, T060, T063, T090, T091, T092, T097. INV-LEDGER-003 remains pending with FEAT-002 (payouts). `make check` passes; traceability reports 22 active / 5 pending.

## 2026-11-06 — slice 4 (timeout, status lookup, receipt)

- Range: `815bd16..HEAD`
- Reviewers: two fresh-context read-only subagents (timeout/money correctness; receipt/contract/test isolation)
- Verdict: **blocked** on three findings; all fixed before slice 5.

### Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `ContributionTimeoutJob`, `PaystackHttpClient.verify` | A partner `failed` charge was applied as `PAYMENT_TIMED_OUT` (EXPIRED) while the domain table says a valid FAILED poll result becomes FAILED; `abandoned`/`reversed` fell to UNKNOWN and stayed PENDING forever. | AC-CONTRIB-008; domain state machine; Article IV.2 | **Fixed**: PAID/FAILED/NO_PAYMENT/UNKNOWN mapping; `failed`/`reversed` → FAILED, `abandoned` → EXPIRED, in-flight/unknown → retry. AC-008 amended; test covers paid, failed and abandoned. |
| Blocker | `ContributionStatusIT`, `ContributionStatus`, OpenAPI | AC-013 said the receipt carries amount/fees/total while OpenAPI puts them beside `reference`/`confirmedAt`; the test checked amounts only while PENDING and accepted any timestamp. | AC-CONTRIB-013; Article II.4 | **Fixed**: AC-013 wording aligned with OpenAPI; the test now asserts exact fee/total values and parses `confirmedAt`. |
| Blocker | `ContributionService.handle` | Unknown provider reference and amount mismatch were WARN-only; a confirmed payment could be discarded without an alert. | AC-CONTRIB-020/021; Article I.5, IX.2 | **Fixed**: `AlertPublisher` (logging in prod, recording in tests); both criteria now have active tests; late-success alert is published after commit. |
| Should-fix | `ContributionTimeoutJob` | One transaction held `FOR UPDATE` locks across up to 100 provider HTTP calls; one failure rolled back the batch. | AC-CONTRIB-008; Article IV | **Fixed**: due rows are selected, verification runs outside the lock, each result is applied in its own transaction with per-row failure isolation. |
| Should-fix | `ContributionRepository`, `ContributionTimeoutIT` | Eligibility and receipt times mixed DB `now()` with the injected clock; the AC-008 test did not prove the cutoff. | Article III.4; AC-CONTRIB-008 | **Fixed**: `created_at`/`updated_at`/`occurred_at`/`confirmed_at` all use the injected clock; the test now leaves a fresh row PENDING before advancing time. |
| Should-fix | `SchedulingConfiguration` | `@EnableScheduling` ran the job in every test context, mutating other tests' rows. | Article III.4 | **Fixed**: scheduling is gated by `collections.scheduling.enabled` and disabled in the test harness. |
| Should-fix | `PaystackHttpClient.verify` | A null `data.status` threw NPE and rolled back the batch; no timeouts; amount/currency not checked. | AC-CONTRIB-008; Article IX.1 | Null guard fixed; timeouts and amount/currency validation remain T097. |
| Should-fix | timeout job | UNKNOWN/null-reference rows keep the oldest timestamps and can starve newer due rows; EXPIRED/FAILED are never re-polled; stale INITIATED rows are never swept. | AC-CONTRIB-008/016/019 | T090 reconciliation/attempt tracking; INITIATED sweep needs a spec decision. |
| Should-fix | `ContributionStatusController`, `PublicContractTest` | The new GET had no 404 or phone-free contract test. | AC-CONTRIB-011/013; OpenAPI | **Fixed**: both tests added. |
| Note | `ContributionRepository` | `confirmed_at` has no backfill for rows confirmed before this change. | AC-CONTRIB-013 | No pre-release data; V3 backfill only if needed. |
| Note | `V1__goal_and_contribution.sql` | Partial index was on `created_at` while the job filters `updated_at`. | AC-CONTRIB-008 | **Fixed**: index moved to `updated_at`. |

### Follow-ups

- Remaining should-fix: T090 (reconciliation, starvation, stale INITIATED, concurrency), T091 (structured events/metrics), T092 (rate limits, secrets), T097 (timeouts, verify amount/currency, raw bytes, replay decision).
- CODEOWNER review for `ledger/` and `payments/` is still required before release; AI review does not replace it.
