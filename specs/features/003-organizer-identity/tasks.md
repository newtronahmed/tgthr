# Tasks: Organizer identity and goal creation

Order is test-first: the failing test comes before the code. `[P]` marks tasks that can run in
parallel because they touch different files. Local progress is ticked when the task's tests are
green on `main`; release still requires a pull request.

## Setup

- [ ] T001 Spike the Paystack sandbox MoMo account-resolution endpoint; record the request, response and name format (no real provider in automated tests)
- [ ] T002 Accept ADR 0009 (SMS provider adapter) and ADR 0010 (opaque sessions); if the spike fails, amend the plan before coding
- [ ] T003 Update `specs/api/openapi.yaml`: cookie auth scheme, OTP/profile/goal schemas, organizer endpoints, 401/403/404/409/422/429/503 problems
- [ ] T004 Add configuration keys: `WEB_ORIGIN`, `SMS_PROVIDER` (fake/logging), `SESSION_TTL`, `OTP_TTL`, `OTP_MAX_ATTEMPTS`, `OTP_RESEND_SECONDS`; no secrets in the repository
- [ ] T005 Write `V3__organizer_identity.sql`: users, user_sessions, otp_challenges, goals additions, goal_events, indexes, append-only triggers
- [ ] T006 Extract the shared `PhoneCipher` from `PayerPhoneCipher`; keep contribution behaviour green; add unit tests for normalize/encrypt/hash
- [ ] T007 Build `FakeSmsSender` and a fake `AccountResolver` for tests; wire them in the test harness
- [ ] T008 [P] Build the generic scoped rate limiter for OTP and goal creation; unit tests with a mutable clock

## Slice 1 (P1): Sign-in, sessions and limits

Criteria: AC-ORG-001, AC-ORG-002, AC-ORG-003, AC-ORG-004, AC-ORG-010, AC-ORG-011, AC-ORG-014, AC-ORG-015, INV-IDENT-001, INV-IDENT-002, INV-IDENT-003

- [ ] T110 Write the failing `OrganizerAuthIT` for AC-ORG-001: valid phone, 202, one fake SMS, no session
- [ ] T111 Implement OTP request, normalization and challenge storage until T110 is green
- [ ] T112 Write the failing test for AC-ORG-002: verify a live code, cookie attributes, token rotation, `/organizer/me`
- [ ] T113 Implement OTP verify, user creation and opaque sessions until T112 is green
- [ ] T114 Write the failing tests for AC-ORG-003: wrong, expired, reused and over-attempted codes; no session
- [ ] T115 Implement attempt counting and challenge burn until T114 is green
- [ ] T116 Write the failing tests for AC-ORG-004 and AC-ORG-015: per-phone/IP 429s and the 60-second resend window
- [ ] T117 Implement OTP rate limits and resend invalidation until T116 is green
- [ ] T118 Write the failing tests for AC-ORG-010: missing, expired and revoked sessions return 401
- [ ] T119 Implement the organizer auth filter and session lookup until T118 is green
- [ ] T120 Write the failing test for AC-ORG-011: cross-site state-changing requests return 403
- [ ] T121 Implement the same-origin filter and configure `WEB_ORIGIN` until T120 is green
- [ ] T122 Write the failing test for AC-ORG-014: logout revokes the session
- [ ] T123 Implement logout and cookie clearing until T122 is green
- [ ] T124 Write the failing `OrganizerIdentityInvariantsTest` for INV-IDENT-001/002/003
- [ ] T125 Implement the remaining session/challenge/phone invariants until T124 is green
- [ ] T126 Refactor the identity module; keep the suite green

## Slice 2 (P1): Goal creation, destination lock and ownership

Criteria: AC-ORG-005, AC-ORG-006, AC-ORG-007, AC-ORG-008, AC-ORG-009, AC-ORG-012, AC-ORG-013

- [ ] T130 Write the failing `OrganizerGoalIT` for AC-ORG-005: authenticated create, 201, unguessable slug, public page, no ledger
- [ ] T131 Implement goal creation, slug generation and persistence until T130 is green
- [ ] T132 Write the failing tests for AC-ORG-006: fixed and open amount rules, invalid input, no row
- [ ] T133 Implement goal-draft validation until T132 is green
- [ ] T134 Write the failing tests for AC-ORG-007: resolved name, ADMIN_MOMO match, unresolvable, partner outage
- [ ] T135 Implement `AccountResolver` and destination locking until T134 is green
- [ ] T136 Write the failing test for AC-ORG-008: same key returns the original, one row
- [ ] T137 Implement the database-backed goal idempotency path until T136 is green
- [ ] T138 Write the failing tests for AC-ORG-009: own list/read only, another organizer gets 404
- [ ] T139 Implement organizer goal queries and ownership checks until T138 is green
- [ ] T140 Write the failing test for AC-ORG-013: owner closes, contributions return 409, repeat is a no-op
- [ ] T141 Implement the close transition and `goal_events` row until T140 is green
- [ ] T142 Write the failing `OrganizerContractTest` for AC-ORG-012: no phone in responses; encrypted columns; no phone in logs
- [ ] T143 Make the DTOs and logging phone-free until T142 is green
- [ ] T144 Refactor the goal creation path; keep the suite green

## Slice 3 (P1): Organizer web

Criteria: AC-ORG-001, AC-ORG-002, AC-ORG-005, AC-ORG-013 (web flows)

- [ ] T150 Add BFF routes for auth and organizer goals; forward cookies, `Set-Cookie`, `Origin` and `Cookie`; test the relay
- [ ] T151 Build `/signin` (phone → code → names) against the fake/development SMS sender; show errors and the resend timer
- [ ] T152 Build the organizer dashboard with own goals, raised totals, status and a create CTA
- [ ] T153 Build the goal wizard and review card from the prototype; validate amounts and destination fields
- [ ] T154 Add close and logout actions with confirmations
- [ ] T155 Write the Playwright E2E: sign in, create a goal, see it on the dashboard and public page, close it
- [ ] T156 Refactor the organizer web; keep lint, tests, build and budget green

## Hardening

- [ ] T160 Add events, metrics and alerts for OTP, sessions, goal creation and destination failures
- [ ] T161 Abuse tests: OTP pumping, enumeration, cross-tenant access, session theft and replay
- [ ] T162 Update `docs/runbooks/` with SMS provider and organizer sign-in operations
- [ ] T163 Run `make check`, the web checks and E2E; remove every `@Disabled` marker for FEAT-003
- [ ] T164 Independent review at the slice boundaries; fix blockers before the next slice
