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

## 2026-11-06 — hardening T090/T091

- Verdict: pass with follow-ups (hardening partially closed).
- Added: AC-CONTRIB-019 active test (prompt rejection → FAILED); concurrency tests for same-key creates and duplicate SUCCESS callbacks; amount cap (GHS 100,000) in spec/OpenAPI/validation; malformed signed webhook → 422 `INVALID_WEBHOOK`; TRUNCATE guards on both append-only tables; bootstrap `X-Correlation-Id` filter; key=value structured events; Micrometer counters for create/prompt/callback/confirm/timeout.
- Split remaining should-fix into **T097** (provider timeouts, currency/event whitelist, raw-byte signature, replay decision) and **T098** (goal-acceptance lock, UUID/randomness seams, production grants, prompt-timeout coverage, reconciliation and alerts for stuck/starved PENDING and stale INITIATED).
- Traceability: 27 active / 3 pending (AC-009 retry, AC-010 feed, INV-LEDGER-003 payout scope).

## 2026-11-06 — slice 5 (retry and anonymous feed)

- Verdict: pass.
- AC-CONTRIB-009: retry after a terminal contribution creates a new contribution; the old row is unchanged; two prompts for two attempts.
- AC-CONTRIB-010: `GET /public/goals/{slug}` now returns the public goal with raised total, contributor count, payout rules and a 20-item feed; anonymous items omit the display name, named items keep it.
- Traceability: 29 active / 1 pending (INV-LEDGER-003 is payout scope, FEAT-002).

## 2026-11-06 — slice 5 boundary (hardening, retry, public goal feed)

- Range: `ce7c93d..HEAD`
- Reviewers: two fresh-context read-only subagents (money/timeout and contract/privacy). The first money prompt returned empty on the first attempt; a narrower retry completed.
- Verdict: **blocked** on one privacy-evidence gap and one latent money gap; both fixed. Remaining items are scheduled.

### Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `PublicContractTest` | AC-011's test never called the new `GET /public/goals/{slug}`, so "any public endpoint" was not proven for the most likely leak site. | AC-CONTRIB-011; Article V.3 | **Fixed**: the test now fetches the public goal and asserts no phone. |
| Blocker | `ContributionRepository.goalRaised` | The public raised total summed CONTRIBUTION credits only; `REVERSAL` debits would inflate it once refunds exist. | Domain ledger; Article I.1 | **Fixed**: raised nets REVERSAL debits; synthetic reversal test added. |
| Should-fix | `PublicContractTest` | AC-010 did not prove the anonymous name was absent from the raw body, did not include PENDING/FAILED rows to prove filtering, and did not assert the named amount. | AC-CONTRIB-010 | **Fixed**: raw-body assertion, non-confirmed rows added, amount asserted. |
| Should-fix | `ContributionApiIT` | AC-009 covered only FAILED, not EXPIRED; did not assert two rows or distinct provider references. | AC-CONTRIB-009 | **Fixed**: EXPIRED retry, row count and distinct charge bodies asserted. |
| Should-fix | `ContributionCallbackIT` | AC-016 did not assert the late-success alert, nor the malformed-webhook problem code. | AC-CONTRIB-016; T091 | **Fixed**: alert count = 2 and `INVALID_WEBHOOK`/problem content type asserted, including missing `data`. |
| Should-fix | `ContributionService`, `ContributionConfirmationService` | The promised `contributions.created` counter was missing; the confirmed metric/log fired before commit. | T091; Article IX.1 | **Fixed**: created counter added; confirmed metric/log moved to after-commit. |
| Should-fix | `application.yml` | Correlation IDs reached the response/MDC but not the log pattern. | T091; Article IX.1 | **Fixed**: console pattern includes `%X{correlationId}`. |
| Should-fix | `PublicGoalView`, `PublicGoalService`, OpenAPI | The public goal omitted the goal's minimum amount; required fields/payout keys were not contract-tested; three reads could straddle commits. | Spec rule 3; Article II.4 | **Fixed**: `minimumAmountPesewas` added, contract fields asserted, service reads in a repeatable-read transaction; feed order/limit and contributor-count semantics documented. |
| Should-fix | `PublicContractTest`, `ContributionApiIT` | Amount cap boundary was only tested on rejection. | Spec resolved decision | **Fixed**: 10,000,000 pesewas accepted test added. |
| Should-fix | `PaystackWebhookMapper` | Missing/unknown `event`, missing reference/amount, currency and status are not validated; a signed malformed event can fail a pending contribution. | T097; AC-CONTRIB-005/006 | Open as **T097** (event/status/currency validation before state change). |
| Should-fix | timeout job | Verify amount/currency is not checked; EXPIRED/FAILED rows are never re-polled; the SKIP LOCKED claim does not survive the statement. | T097/T098 | Open; duplicate polling is safe because resolution is idempotent (comment corrected). |
| Note | `contributorCount` | Counts confirmed contributions, not distinct people; ambiguous name, documented in OpenAPI, decision folded into T098. | OpenAPI | T098 decision. |
| Note | tests | 23 `@Disabled` placeholders remain; traceability is active-if-any-test. | Article III.2 | T093. |

### Follow-ups

- T097: provider timeouts, event/status/currency validation, verify amount/currency, raw-byte signature, replay decision.
- T098: re-poll EXPIRED/FAILED, claim semantics, concurrency barriers, contributor-count decision, stale INITIATED, grants.
- T093/T094/T096: remove placeholders, mark the spec Implemented, run the sandbox suite.

## 2026-11-06 — T097/T098 provider hardening and close-out

- Verdict: pass with one residual hardening task.
- Provider: connect/read timeouts (configurable), verified in the prompt-timeout test; callback event/status/currency validation; verify amount/currency validation with a mismatch alert; raw-byte HMAC verification; replay decision recorded (reference idempotency, no timestamp window).
- Reconciliation: EXPIRED/FAILED rows are re-polled for 24 hours; a paid row confirms with a late-success alert. The `SKIP LOCKED` claim semantics are documented (duplicate polling is safe).
- Test support: the fake now has slow, abandoned and wrong-amount scenarios, echoes amounts for verify, and runs on a thread pool.
- Close-out: FEAT-001 placeholders removed; the only remaining disabled test is INV-LEDGER-003 (payout scope, FEAT-002). Spec marked Implemented. Traceability 29 active / 1 pending.
- Residual: **T098** (goal-acceptance lock, UUID/randomness seams, concurrency test barriers, starvation/stale-INITIATED alerts) and **T092** (rate limits, secrets, phone redaction). T095 web and T096 sandbox remain for delivery.
- CODEOWNER review for `ledger/` and `payments/` is still required before release.

## 2026-11-06 — residual hardening (T092/T098)

- Verdict: pass.
- Registration now holds `FOR SHARE` on the goal row for the acceptance check and insert; the idempotent insert uses `ON CONFLICT DO NOTHING`; a barrier `IdGenerator` forces the check-then-insert race in `ContributionIdempotencyRaceIT` (one row, one prompt).
- `IdGenerator` and `SecureRandom` are injected; registration uses the injected `Clock`.
- Stale INITIATED rows are polled (paid → CONFIRMED, no payment → EXPIRED); PENDING rows older than twice the timeout raise `STUCK_PENDING`; terminal re-polling remains.
- Rate limiting: in-memory fixed window per client IP and per hashed phone; 429 `RATE_LIMITED`; unit and integration tests.
- Secrets/redaction: no committed database password default; the request `toString` omits the phone number.
- Traceability 29 active / 1 pending (INV-LEDGER-003, FEAT-002). Remaining delivery: T095 web, T096 sandbox, CODEOWNER review.

## 2026-11-06 — web slice (public goal, checkout, receipt)

- Range: `dc1de62..HEAD`
- Reviewer: one fresh-context read-only subagent (contract, privacy, money display, UX, Next.js).
- Verdict: **blocked** on three findings; all fixed.

### Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `web/components/checkout.tsx` | The quote stayed visible while the amount changed, so Pay could be pressed while the screen showed the previous total. | AC-CONTRIB-022; ADR 0007 | **Fixed**: `quoteMatches` gates the total and the Pay button; submit refuses a stale quote. |
| Blocker | `web/components/checkout.tsx` | A create response of `CONFIRMED` (early callback) was treated as an error and the guest was invited to retry, risking a second payment. | AC-CONTRIB-018; Article I.5 | **Fixed**: `PENDING` and `CONFIRMED` both navigate to the receipt. |
| Blocker | `web/lib/proxy.ts`, rate limits | The proxy dropped the client IP, so all guests shared the Next server's IP and the 30/min limit became global. | AC-CONTRIB-001/022 | **Fixed**: proxies forward `X-Forwarded-For`; Spring uses forwarded headers; quotes are also per-IP. The API must stay private. |
| Should-fix | `web/lib/money.ts` | The parser silently transformed `-100`, `1e3`, `10,50` and extra decimals. | ADR 0007; Article I.3 | **Fixed**: strict `^\d+(\.\d{1,2})?$`, integer formatting, unit tests; link parsing handles query/hash. |
| Should-fix | `web/components/checkout.tsx` | The idempotency key was reset on every failure and lost on reload. | AC-CONTRIB-015 | **Fixed**: `{key, signature}` in sessionStorage per goal; kept on 5xx/network, cleared on 4xx and after a terminal result. |
| Should-fix | `web/app/c/[id]/page.tsx` | FAILED/EXPIRED said "nothing was charged" although a late SUCCESS can still confirm. | AC-CONTRIB-016 | **Fixed**: copy says no successful payment is recorded yet and offers "Check again"; REFUNDED has its own branch; the pending page no longer links back to a new payment. |
| Should-fix | `web/app/g/[slug]/page.tsx` | Checkout appeared for past-deadline goals because only `status` was checked. | AC-CONTRIB-012 | **Fixed**: deadline is evaluated server-side before rendering checkout. |
| Should-fix | `web/app/**`, `web/components/**` | Small low-contrast text, an unnamed switch, colour-only chip selection, 14px inputs (iOS zoom), motion ignored reduced-motion. | Article VI.2; WCAG | **Fixed**: darker text, labelled switch, `aria-pressed`, 16px mobile inputs, reduced-motion CSS. |
| Should-fix | `web/next.config.ts`, `web/lib/proxy.ts` | No framing/CSP/nosniff headers; receipt indexable; proxies dropped correlation ids and cache headers. | Article VIII.3; IX.1 | **Fixed**: security headers, `poweredByHeader: false`, receipt noindex, no-store and correlation/retry forwarding. |
| Should-fix | `web/package.json`, CI | `npm run lint` failed; no web tests; CI built only. | Article III.1–2 | **Fixed**: lint clean, money/problem unit tests with `criterion()` references, CI runs lint, tests and build. |
| Should-fix | `web/app/c/[id]/page.tsx`, `web/lib/api.ts` | Invalid UUIDs rendered a 500; no error boundary; 404 copy was goal-specific. | OpenAPI 404 | **Fixed**: 400/404 map to not-found, branded error boundary, generic 404 copy. |
| Should-fix | PWA/performance | No service worker/offline fallback, PNG/maskable/Apple icons, or page-weight budget ADR. | ADR 0003; Article VI.2 | **T099**. |
| Should-fix | Tests | No browser E2E checkout/receipt flow. | Article III | **T100**. |
| Note | `web/app/g/[slug]/page.tsx` | Goal metadata/page now share a `cache()`d fetch; per-goal OG images remain a follow-up. | ADR 0003 | Future polish. |

### Follow-ups

- T099: PWA offline/service worker, PNG + maskable + Apple icons, page-weight budget ADR and CI measurement.
- T100: browser E2E checkout/receipt against the fake provider.
- Manual Paystack sandbox (T096) and the CODEOWNER review for `ledger/` and `payments/` remain before release.

## 2026-11-06 — web close-out (T099/T100)

- Verdict: pass.
- PWA: service worker with a static shell and offline fallback, network-first navigations, cache-first static assets; API and personal pages are never cached. 192/512/maskable PNG icons and an Apple touch icon are generated from the brand SVG. `app/offline` is precached.
- Performance: ADR 0008 sets a 220 KB JS / 20 KB CSS gzipped budget; `npm run budget` measures 183.5 / 9.1 KB and CI enforces it.
- E2E: `@playwright/test` runs the full guest flow (goal → quote → PENDING → signed fake callback → receipt) against the real backend in CI and against the stub locally. The fake provider lives in `tools/paystack-fake`.
- Traceability: 30 active / 1 pending (INV-LEDGER-003, FEAT-002).
- Remaining before release: manual Paystack sandbox (T096), CODEOWNER review for `ledger/` and `payments/`, and a PR merge.
