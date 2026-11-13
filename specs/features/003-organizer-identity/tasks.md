# Tasks: Organizer identity and goal creation

Order is test-first: the failing test comes before the code. `[P]` marks tasks that can run in
parallel because they touch different files. Local progress is ticked when the task's tests are
green on `main`; release still requires a pull request.

## Setup

- [x] T001 Spike the Paystack sandbox MoMo account-resolution endpoint; record the request, response and name format (no real provider in automated tests) (`docs/runbooks/paystack-account-resolve-spike.md`)
- [x] T002 Accept ADR 0009 (SMS provider adapter) and ADR 0010 (opaque sessions); if the spike fails, amend the plan before coding
- [x] T003 Update `specs/api/openapi.yaml`: cookie auth scheme, OTP/profile/goal schemas, organizer endpoints, 401/403/404/409/422/429/503 problems
- [x] T004 Add configuration keys: `WEB_ORIGIN`, `SMS_PROVIDER` (fake/logging), `SESSION_TTL`, `OTP_TTL`, `OTP_MAX_ATTEMPTS`, `OTP_RESEND_SECONDS`; no secrets in the repository
- [x] T005 Write `V3__organizer_identity.sql`: users, user_sessions, otp_challenges, goals additions, goal_events, indexes, append-only triggers
- [x] T006 Extract the shared `PhoneCipher` from `PayerPhoneCipher`; keep contribution behaviour green; add unit tests for normalize/encrypt/hash
- [x] T007 Build `FakeSmsSender` and a fake `AccountResolver` for tests; wire them in the test harness
- [x] T008 [P] Build the generic scoped rate limiter for OTP and goal creation; unit tests with a mutable clock

## Slice 1 (P1): Sign-in, sessions and limits

Criteria: AC-ORG-001, AC-ORG-002, AC-ORG-003, AC-ORG-004, AC-ORG-010, AC-ORG-011, AC-ORG-014, AC-ORG-015, INV-IDENT-001, INV-IDENT-002, INV-IDENT-003

- [x] T110 Write the failing `OrganizerAuthIT` for AC-ORG-001: valid phone, 202, one fake SMS, no session
- [x] T111 Implement OTP request, normalization and challenge storage until T110 is green
- [x] T112 Write the failing test for AC-ORG-002: verify a live code, cookie attributes, token rotation, `/organizer/me`
- [x] T113 Implement OTP verify, user creation and opaque sessions until T112 is green
- [x] T114 Write the failing tests for AC-ORG-003: wrong, expired, reused and over-attempted codes; no session
- [x] T115 Implement attempt counting and challenge burn until T114 is green
- [x] T116 Write the failing tests for AC-ORG-004 and AC-ORG-015: per-phone/IP 429s and the 60-second resend window
- [x] T117 Implement OTP rate limits and resend invalidation until T116 is green
- [x] T118 Write the failing tests for AC-ORG-010: missing, expired and revoked sessions return 401
- [x] T119 Implement the organizer auth filter and session lookup until T118 is green
- [x] T120 Write the failing test for AC-ORG-011: cross-site state-changing requests return 403
- [x] T121 Implement the same-origin filter and configure `WEB_ORIGIN` until T120 is green
- [x] T122 Write the failing test for AC-ORG-014: logout revokes the session
- [x] T123 Implement logout and cookie clearing until T122 is green
- [x] T124 Write the failing `OrganizerIdentityInvariantsTest` for INV-IDENT-001/002/003
- [x] T125 Implement the remaining session/challenge/phone invariants until T124 is green
- [ ] T126 Refactor the identity module; keep the suite green

## Slice 2 (P1): Goal creation, destination lock and ownership

Criteria: AC-ORG-005, AC-ORG-006, AC-ORG-007, AC-ORG-008, AC-ORG-009, AC-ORG-012, AC-ORG-013

- [x] T130 Write the failing `OrganizerGoalIT` for AC-ORG-005: authenticated create, 201, unguessable slug, public page, no ledger
- [x] T131 Implement goal creation, slug generation and persistence until T130 is green
- [x] T132 Write the failing tests for AC-ORG-006: fixed and open amount rules, invalid input, no row
- [x] T133 Implement goal-draft validation until T132 is green
- [x] T134 Write the failing tests for AC-ORG-007: resolved name, ADMIN_MOMO match, unresolvable, partner outage
- [x] T135 Implement `AccountResolver` and destination locking until T134 is green
- [x] T136 Write the failing test for AC-ORG-008: same key returns the original, one row
- [x] T137 Implement the database-backed goal idempotency path until T136 is green
- [x] T138 Write the failing tests for AC-ORG-009: own list/read only, another organizer gets 404
- [x] T139 Implement organizer goal queries and ownership checks until T138 is green
- [x] T140 Write the failing test for AC-ORG-013: owner closes, contributions return 409, repeat is a no-op
- [x] T141 Implement the close transition and `goal_events` row until T140 is green
- [x] T142 Write the failing `OrganizerContractTest` for AC-ORG-012: no phone in responses; encrypted columns; no phone in logs
- [x] T143 Make the DTOs and logging phone-free until T142 is green
- [x] T144 Refactor the goal creation path; keep the suite green

## Slice 3 (P1): Organizer web

Criteria: AC-ORG-001, AC-ORG-002, AC-ORG-005, AC-ORG-013 (web flows)

- [x] T150 Add BFF routes for auth and organizer goals; forward cookies, `Set-Cookie`, `Origin` and `Cookie`; test the relay
- [x] T151 Build `/signin` (phone → code → names) against the fake/development SMS sender; show errors and the resend timer
- [x] T152 Build the organizer dashboard with own goals, raised totals, status and a create CTA
- [x] T153 Build the goal wizard and review card from the prototype; validate amounts and destination fields
- [x] T154 Add close and logout actions with confirmations
- [x] T155 Write the Playwright E2E: sign in, create a goal, see it on the dashboard and public page, close it
- [x] T156 Refactor the organizer web; keep lint, tests, build and budget green

## Hardening

- [x] T160 Add events, metrics and alerts for OTP, sessions, goal creation and destination failures
- [x] T161 Abuse tests: OTP pumping, enumeration, cross-tenant access, session theft and replay
- [ ] T162 Update `docs/runbooks/` with SMS provider and organizer sign-in operations (Arkesel adapter and `docs/runbooks/sms-operations.md` implemented; live key returned 401 `Invalid key`, so the real OTP smoke test awaits a re-copied Main API key and approved sender ID)
- [x] T163 Run `make check`, the web checks and E2E; remove every `@Disabled` marker for FEAT-003
- [x] T164 Independent review at the slice boundaries; fix blockers before the next slice
- [x] T165 Add retention/cleanup for expired `otp_challenges` and `user_sessions` (scheduled job and index review)
- [x] T166 Block updates to `goals.destination_*` once the create path lands (trigger or constraint), proving destination immutability
- [x] T167 Decide whether a display name is required at first verification; amend the spec and OpenAPI together, then enforce in the service
- [x] T168 Forward `Origin`, `Cookie` and `X-Forwarded-For` in the BFF auth/organizer routes and cover the relay in the web E2E
- [ ] T169 Add a deterministic `Clock` test seam for OTP/session expiry and assert the 60-second and 30-day production defaults
- [x] T170 Serialize OTP requests per phone (advisory lock or one-active-challenge index) and add a concurrency test
- [x] T171 Add the production `AccountResolver` (Paystack `/bank/resolve` with the MoMo telco code) after T001 and test it against the fake partner (`PaystackAccountResolver` with success/not-found/transient/5xx/network tests)
- [x] T172 Use only the stored legal name for ADMIN_MOMO, require the destination to be the organizer's own phone, and stop mutating identity at goal create
- [x] T173 Add a same-key concurrency test for goal creation (fix landed: resolution outside the write transaction)
- [ ] T174 Add a slug-collision test with an injectable slug source (fix landed: all-constraint ON CONFLICT)
- [x] T175 Add AC-ORG-007 unresolvable, legal-name-required, wrong-owner and name-normalization coverage
- [x] T176 Assert organizer raised totals with a confirmed contribution and a reversal
- [x] T177 Snapshot ledger count, re-check close status, document 409 and test FROZEN goals
- [x] T178 Assert organizer phone at rest and scan public/get/close bodies for phones
- [x] T179 Expose `secondApproverThresholdPesewas` in the public payout rules
- [ ] T180 Add concurrent same-key, key-reuse-after-422 and unique test-phone fixtures
- [x] T181 Move `GoalNotFoundException` mapping to a goal/shared advice
- [x] T182 Decide whether payout destinations accept E.164 as well as local numbers
- [x] T183 Fix null-body 204/205/304 in the BFF and cover logout in E2E
- [x] T184 Reuse one wizard idempotency key per attempt
- [x] T185 Add try/finally and retry errors to the web mutations
- [x] T186 Add `aria-pressed` and a non-colour selected marker to the template picker
- [x] T187 Allow a zero approver threshold and hide it when approval is off
- [x] T188 Require the `e2e` profile for dev SMS/OTP/resolver beans
- [x] T189 Add mobile viewport and closed-goal 409 coverage to the organizer E2E
- [x] T190 Ignore `*.tsbuildinfo`
- [x] T191 Bound BFF request bodies
- [x] T192 Map malformed goal ids to 404
- [ ] T193 Consider a `__Host-` session cookie name
- [x] T194 Normalize the phone in the sign-in UI before sending
- [ ] T195 Finish CSP tightening (HSTS added; nonce/hash CSP and header tests remain)
- [x] T196 Align OpenAPI goal defaults/nullability and update status/docs
- [x] T197 Add the `(phone_hash, created_at DESC)` OTP index
- [ ] T198 Complete the trusted-IP contract for all BFF routes and add forged-header/per-IP tests (auth routes now use x-real-ip only)

## Review follow-ups (FEAT-003 identity slice)

| Item | Owner | Task |
| --- | --- | --- |
| Alert rules and runbook for OTP/SMS/destination failures | backend | T160 |
| Production SMS adapter (Arkesel/Hubtel) and live sandbox check | backend | T001, T162 |
| Production destination resolver and live MoMo check | backend | T001, T171 |
| Destination immutability guard with the goal-create slice | backend | T166 |
| Display-name contract decision | spec owner | T167 |
| BFF Origin/Cookie/forwarded-IP relay | web | T168 |
| Deterministic clock tests for expiry defaults | test | T169 |
| OTP request concurrency guard | backend | T170 |
| Goal creation limiter | backend | T008 |
| Goal destination DB guard | backend | T166 |
| Goal slice concurrency/slug/header tests | backend | T173/T174/T180 |
| Goal exception mapping | backend | T181 |
| Payout destination E.164 decision | spec owner | T182 |
| Mobile/closed-goal E2E | web | T189 |
| `__Host-` cookie naming | backend | T193 |
| CSP/HSTS hardening | web | T195 |
| Trusted client IP / per-IP tests | web/backend | T198 |
| Slug-collision test | backend | T174 |
| Unique fixture cleanup | test | T180 |
| Deterministic clock tests | test | T169 |
| CSP nonce/hash tests | web | T195 |
| Retention for OTP challenges and sessions | backend | T165 |
