# Review: FEAT-003 organizer identity, first slice

- Date: 2026-11-07
- Range: `b1ef3d7..working tree`
- Reviewer: fresh-context subagent (read-only); supplements CODEOWNER review
- Verdict: pass with follow-ups (blocker fixed before the next slice)

## Findings

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `identity/OtpService.verify`, `identity/AuthController` | Verification had no per-phone/per-IP rate limit; `/auth/otp/verify` documented 429 but could never return it. | AC-ORG-004, spec rule 3 | Fixed: `OtpRateLimiter.checkVerify`, controller passes the client IP, and `OrganizerRateLimitIT.verificationRateLimitsReturn429` covers it. |
| Should-fix | `identity/SameOriginFilter` | `/auth/otp/request` and `/auth/otp/verify` were exempt, allowing login CSRF and SMS-pumping form posts. | AC-ORG-011, Article VIII | Fixed: filter now covers both; `crossSiteStateChangesAreRejected` proves a cross-site verify is 403 and the challenge still verifies same-origin. |
| Should-fix | `identity/OtpService.request`, `OtpChallengeRepository` | The resend window only looked at active challenges, so a burned or consumed code could be replaced immediately. | AC-ORG-015, spec rule 2 | Fixed: `findLatest` drives the window regardless of state; tests cover burned and post-verify resends. |
| Should-fix | `identity/OtpService.request` | `sms.send` ran inside the DB transaction, holding a connection across the provider call and losing a delivered code on commit failure. | AC-ORG-001, Article IX | Fixed: challenge preparation commits in a `TransactionTemplate`, then the SMS is sent; a send failure consumes the challenge and emits `identity.sms.failed`. |
| Should-fix | `identity/*`, `web/CorrelationIdFilter` | No OTP/session/auth metrics; filter rejections bypassed the correlation id filter. | Article IX.1 | Partially fixed: counters for request/verify/session/rejections and `HIGHEST_PRECEDENCE` on the correlation filter. Alert rules and the runbook remain T160. |
| Should-fix | `OrganizerAuthIT.validOtpCreatesARotatedSession`, `crossSiteStateChangesAreRejected` | Rotation/expiry and "nothing changes" were not proven. | AC-ORG-002, AC-ORG-011 | Fixed: asserts `Max-Age=2592000`, old session remains valid (multi-device per ADR 0010), and the session survives rejected cross-site requests. |
| Should-fix | `OrganizerRateLimitIT` | Only request-per-phone was covered. | AC-ORG-004 | Fixed for verify; forwarded-IP/per-IP coverage remains T161/T168. |
| Should-fix | `OrganizerAuthIT` | `Thread.sleep` and the system clock; production 60-second and 30-day defaults are not exercised. | Article III.4 | T169. |
| Note | `identity/OtpService.request` | Two concurrent requests for one phone can both pass the resend check and send two codes. | AC-ORG-015, Article IV | T170 (serialize per phone or a partial unique index). |
| Note | `identity/OtpRateLimiter` | In-memory, no eviction; keys on `getRemoteAddr`; the BFF must forward `X-Forwarded-For`. | Spec rule 3, plan risk | T008/T168. |
| Note | `V3__organizer_identity.sql` | No retention for `otp_challenges`/`user_sessions`; destination immutability has no DB guard yet. | INV-IDENT-002, spec rules 8/13 | T165/T166. |
| Note | `OtpVerify`, OpenAPI | Display-name requirement at first creation is ambiguous. | Spec rule 5 | T167 (spec and OpenAPI change together). |
| Note | `SmsConfiguration` | The `logging` sender logs the full code; must remain dev-only. | Article VIII | T162. |
| Note (verified) | `security/PhoneCipher` | Contribution behaviour is preserved: same derivations, AES-GCM layout and lookup hash; phone unit tests added. | Article VIII.2 | T006 closed. |

## Follow-ups

- T160 alerts/runbook; T161 abuse tests; T162 production SMS adapter and dev-only logging; T163 final gate.
- T165 retention; T166 destination immutability; T167 display-name contract; T168 BFF origin/forwarded-IP relay; T169 deterministic clock; T170 OTP request serialization.

## Goal creation, ownership and close slice

- Date: 2026-11-07
- Range: identity slice working tree..goal slice working tree
- Reviewer: fresh-context subagent (read-only); supplements CODEOWNER review
- Verdict: pass with follow-ups (blocker fixed)

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `goal/OrganizerGoalService`, `goal/OrganizerGoalProblemAdvice` | Goal create/close/destination failures emitted no events or metrics (Article IX MUST). | Article IX.1, T160 | Fixed: `goals.created`, `goals.closed`, `destination.resolve.failed{reason}` counters and phone-free logs; asserted in `OrganizerGoalIT`. |
| Should-fix | `goal/OrganizerGoalService` | Goal create accepted a self-declared legal name and overwrote the account name, defeating the name match. | AC-ORG-007, rules 5/8 | Fixed: the stored legal name only; `legalName` removed from `CreateGoal`; no identity mutation. |
| Should-fix | `goal/OrganizerGoalService` | `ADMIN_MOMO` did not require the destination to be the organizer's own phone. | AC-ORG-007, rule 5 | Fixed: compares the destination hash to the session user's `phone_hash`; tests cover wrong-owner. |
| Should-fix | `goal/OrganizerGoalService`, `OrganizerGoalRepository` | Slug collisions aborted the transaction, so retry could not work. | AC-ORG-005 | Fixed: `ON CONFLICT DO NOTHING` across constraints, distinguish idempotency by lookup, retry slug; collision test remains T174. |
| Should-fix | `goal/OrganizerGoalService` | Partner resolution ran inside the write transaction and same-key requests could both call the partner. | Article IV.1 | Fixed: resolution and the idempotency read happen before a short write transaction; concurrency test remains T173. |
| Should-fix | `OrganizerGoalIT`, `FakeAccountResolver` | AC-ORG-007 lacked unresolvable, legal-name-required, wrong-owner and name-normalization cases. | AC-ORG-007 | Fixed: fake unresolvable mode, new negative cases and `NameMatcherTest`. |
| Should-fix | `OrganizerGoalIT` | AC-ORG-009 never asserted raised totals; the duplicate ledger formula could drift. | AC-ORG-009, Article I | Fixed: confirmed contribution plus reversal test asserts 4,000 pesewas / 1 contributor. |
| Should-fix | `OrganizerGoalIT` | AC-ORG-005's ledger join could miss a creation-time entry. | AC-ORG-005, rule 14 | Fixed: total `ledger_entries` snapshot before/after. |
| Should-fix | `OrganizerGoalIT` | AC-ORG-012 did not assert organizer phone at rest or scan public/get/close bodies. | AC-ORG-012, INV-IDENT-003 | Fixed for this slice; invariant test bodies remain T124/T178. |
| Should-fix | `goal/OrganizerGoalService`, OpenAPI | FROZEN close returned 200 if the row changed after the read; 409 was undocumented. | AC-ORG-013, Article IV.2 | Fixed: status re-check and 409 contract; frozen test added. |
| Should-fix | OpenAPI | 429 is documented for create/list but no goal limiter exists. | Article II.4, T008 | T008 (implement or remove), owner backend. |
| Should-fix | `PublicGoalView`, OpenAPI | The new second-approver threshold was missing from the public payout rules. | Article V.5 | Fixed: public `payoutRules` now includes `secondApproverThresholdPesewas`; spec/OpenAPI updated. |
| Note | `V3__organizer_identity.sql` | Destination immutability is application-only until T166. | Article V.1 | T166. |
| Note | Controller/service/OpenAPI | Idempotency-Key enforcement is now 422 for missing/short/blank; concurrency/reuse tests remain. | Rule 9 | T173/T180. |
| Note | `OrganizerGoalIT` | Shared phone literals and no cleanup make reruns order-sensitive. | Article III | T180. |
| Note | `ContributionProblemAdvice` | `GoalNotFoundException` mapping still lives in the contribution module. | Article VII.2 | T181. |
| Note | `UnconfiguredAccountResolver` | Production goal creation still returns 503 until the Paystack resolver exists. | T001/T171 | T171. |
| Note | `SlugGenerator` | 12-byte base64url differs from the plan's Crockford wording; collision test pending. | AC-ORG-005 | T174. |
| Note | `CreateGoalRequest`, OpenAPI | Payout destination accepts only local `0XXXXXXXXX`; spec rule 1 mentions local or E.164. | Spec rule 1 | T182. |
| Verified | `OrganizerGoalRepository`, `Goal`, `ContributionRepository` | No goal path writes a balance or ledger row; raised formula matches CONTRIBUTION CREDIT minus REVERSAL DEBIT; closed goals reject contributions. | Articles I/IV, AC-ORG-013 | Regression test T176. |

## Follow-ups

- T008 goal-creation limiter; T124/T125 identity invariants; T164 final review; T166 destination DB guard; T171 production resolver; T172–T182 as assigned in `tasks.md`.

## Organizer web slice

- Date: 2026-11-08
- Range: goal slice working tree..web slice working tree
- Reviewer: fresh-context subagent (read-only); supplements CODEOWNER review
- Verdict: pass with follow-ups (blocker fixed)

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `web/lib/proxy.ts` | `new Response("", {status:204})` throws in Node, so logout returned 500 and never cleared the cookie. | AC-ORG-014 | Fixed: null body for 204/205/304; E2E now signs out and proves `/organizer` redirects to `/signin`. |
| Should-fix | `web/lib/proxy.ts` | The BFF forwards a client-supplied `X-Forwarded-For`, which the backend trusts for per-IP limits. | AC-ORG-004, Article VIII | T198: trust only a platform IP header and add per-IP tests. |
| Should-fix | `web/components/goal-wizard.tsx` | A new idempotency key on each submit defeats retry safety. | AC-ORG-008 | Fixed: one key per wizard mount; double-submit disabled. |
| Should-fix | client components | Fetch errors left forms stuck and unhandled. | Article IX | Fixed: try/finally with visible retry errors in sign-in, wizard, close and logout. |
| Should-fix | `web/components/signin-flow.tsx` | Spaces in the phone placeholder caused a 422 before normalization. | AC-ORG-001 | Fixed: strip spaces/hyphens before sending. |
| Should-fix | `web/components/goal-wizard.tsx` | Template picker lacked selection semantics for assistive tech. | WCAG 4.1.2 | Fixed: `aria-pressed` and a non-colour check mark. |
| Should-fix | `web/lib/organizer-form.ts`, wizard | Threshold 0 could not be submitted and was shown when approval was off. | OpenAPI | Fixed: zero accepted; field shown only for `ABOVE_THRESHOLD`. |
| Should-fix | `backend/.../UserRepository.java`, sign-in | Names were write-once, so a legal-name typo permanently blocked `ADMIN_MOMO`. | AC-ORG-007, T167 | Fixed: a returning sign-in can correct names; test added. |
| Should-fix | `web/components/close-goal-button.tsx` | One click closed a goal with no confirmation. | AC-ORG-013, T154 | Fixed: confirm/cancel step; E2E covers it. |
| Should-fix | dev beans, runbook | The dev SMS oracle/resolver needed one env flag only. | Article VIII, V.1 | Fixed: `@Profile("e2e")` plus the `dev` property; CI/runbook updated. |
| Should-fix | OpenAPI goal endpoints | 429 documented without a goal limiter. | T008 | Removed from goal list/create until T008. |
| Should-fix | `otp_challenges` index | The resend-window lookup could not use the partial index. | NFR | Fixed: added `(phone_hash, created_at DESC)` index. |
| Note | BFF/Next | Cookie allowlist, `__Host-` naming, CSP/HSTS hardening remain. | Article VIII | T193/T195. |
| Note | BFF | Public POST bodies are now bounded at 32 KB. | Article VIII | Fixed. |
| Note | `web/lib/organizer.ts` | Backend 400 on a malformed goal id now maps to 404. | AC-ORG-009 | Fixed. |
| Note | E2E | Cookie attributes and logout are covered; mobile viewport and closed-goal 409 remain. | AC-ORG-002/013 | T189. |
| Note | Docs | Status, routes and this review are updated; OpenAPI defaults/nullability aligned. | Article II | Fixed. |
| Note | `.gitignore` | `*.tsbuildinfo` ignored. | Hygiene | Fixed. |

## Follow-ups

- T008 goal limiter; T124/T125 invariants; T165 retention; T166 destination guard; T171 production resolver; T173/T174/T180 tests; T181/T182 decisions; T189 mobile/409 E2E; T193 `__Host-` cookie; T195 CSP/HSTS; T198 trusted client IP.

## Hardening slice

- Date: 2026-11-08
- Range: web slice working tree..hardening working tree (branch `feat/003-hardening`)
- Reviewer: fresh-context subagent (read-only); supplements CODEOWNER review
- Verdict: pass with follow-ups (blockers fixed)

| Severity | Location | Finding | Reference | Action |
| --- | --- | --- | --- | --- |
| Blocker | `alerts/LoggingAlertPublisher` | Moving alerts downgraded the production channel to WARN and dropped the `ALERT` token, risking silent money-alert paging. | Article I.5/IX.2 | Fixed: ERROR + `ALERT` restored; `LoggingAlertPublisherTest` asserts level and token. |
| Blocker | `goal/GoalRateLimiter` path | 429 had no counter or event. | Article IX.1 | Fixed: `goals.rate_limited` counter and log in the service. |
| Should-fix | `goal/OrganizerGoalService` | Unexpected resolver failures escaped as 500 without an alert/metric. | AC-ORG-007 | Fixed: caught, alerted and mapped to 503 `DESTINATION_CHECK_UNAVAILABLE`. |
| Should-fix | `goal/OrganizerGoalService` | Resolver received the raw phone while the stored hash was normalized. | T171/T182 | Fixed: normalize once, pass E.164 to the resolver; fake normalizes too. |
| Should-fix | `IdentityRetentionJob` | Hard-coded retention, unindexed predicate, no purge metric/alert. | T165 | Fixed: configurable `IDENTITY_RETENTION`, expression indexes, counters and a failure alert. |
| Should-fix | tests | Known-account enumeration, E.164 hash equivalence, per-column destination lock and alert publisher lacked evidence. | T161/T166/T182 | Fixed with new/expanded tests; all pass. |
| Should-fix | `specs/.../spec.md` | SMS failure was a “gap: needs a criterion”. | Article II | Added AC-ORG-016 and tagged the test; trace is now 50 defined / 49 active / 1 pending. |
| Should-fix | BFF/Next | Trusted-IP contract only partially solved; public contribution routes still trust XFF. | T198 | Auth/organizer routes now use `x-real-ip`; remaining routes/tests T198. |
| Should-fix | Goal limiter / retention | Endpoint 429/window-rollover and purge-failure tests still missing. | T008/T165 | Remaining in T008/T165. |
| Should-fix | Invariant tests | INV-IDENT-002/003 evidence can be stronger (scoped fixtures, keyed-hash proof, log scan). | T124/T178 | Remaining in T124/T178/T180. |
| Note | V4 trigger | Protects all six destination columns and allows close; INSERT/legacy null-lock rows remain outside its scope. | Article V.1 | Accepted; future destination-change path needs a spec decision. |
| Note | OTP lock | Transaction-scoped advisory lock works; `hashtext` is 32-bit. | T170 | Accepted for current volume; consider `hashtextextended`. |
| Note | `next.config.ts` | HSTS added; CSP nonce/hash and header tests remain. | T195 | T195. |
| Verified | Tests | Full backend suite, web unit/lint/build/budget and Playwright desktop + mobile (checkout + organizer, including closed-goal 409) pass locally. | Article III | None. |

## Follow-ups

- T008/T165 test depth; T124/T178/T180 invariant evidence; T169 deterministic clock; T174 slug collision; T193 `__Host-` cookie; T195 CSP; T198 all-route trusted IP/per-IP tests; T171/T162/T001/T096 production adapters and live sandbox.

Paystack MoMo account resolution (2026-11-13): T001 is recorded in
`docs/runbooks/paystack-account-resolve-spike.md`: `GET /bank/resolve` returns the local account
name for the documented test MoMo number (4xx unresolved, 5xx unavailable). T171 adds
`PaystackAccountResolver` behind `ACCOUNT_RESOLVER=paystack`: it converts stored E.164 to the local
MoMo form, maps `MTN/VOD/ATL`, returns null on a 4xx and raises `DESTINATION_CHECK_UNAVAILABLE` on
5xx/transport failures. `FakePaystackServer` serves the resolve endpoint; unit tests cover MTN,
Telecel, not-found, 5xx and missing-network cases. `MomoNumbers` now centralizes the local conversion
used by the payout adapter too. No automated test calls Paystack. Trace stays 91/91/0.
