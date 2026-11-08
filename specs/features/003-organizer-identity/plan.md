# Implementation plan: FEAT-003 Organizer identity and goal creation

| Field | Value |
| --- | --- |
| Feature | FEAT-003 |
| Status | In progress |
| Created | 2026-11-06 |
| Related | specs/features/003-organizer-identity/spec.md, specs/domain/organizer-identity.md, docs/adr/0009-sms-otp-provider.md, docs/adr/0010-opaque-sessions.md |

## Constitution Check

| Article | Pass | How |
| --- | --- | --- |
| I. Money is exact and traceable | Yes | Goal creation posts no ledger entries and stores no balance. Destination phones are encrypted; the ledger is untouched until FEAT-002. |
| II. Spec first | Yes | This spec and the OpenAPI changes land before code. The destination-resolution spike can change the plan, not the accepted criteria. |
| III. Test first | Yes | Every AC and INV gets a test; the SMS and destination resolvers use fakes; time/randomness are injected. |
| IV. Idempotent and consistent | Yes | Goal creation requires an `Idempotency-Key` with a database unique constraint; OTP challenges are single-use. |
| V. Trust is a feature | Yes | Destination is name-matched and locked; safeguards are visible on the public page; no phone numbers are exposed. |
| VI. Guest first | Yes | Public pages stay unchanged. Organizer pages are server-rendered, mobile-first and work in WhatsApp's browser. |
| VII. Simple until proven otherwise | Yes | One monolith. The SMS gateway is an external service behind an adapter (ADR 0009). No new datastore. |
| VIII. Security and privacy | Yes | OTP, session, phone encryption, rate limits, cross-site protection and ownership checks are all specified. |
| IX. Observable | Partial | OTP, session, auth-rejection and cross-site counters plus correlation ids land with this slice; the alert rules and runbook are T160. |

## Technical context

- Modules: new `identity`, extended `goal`, extended `payments` (account resolution), shared `security` (phone cipher), `web` pages and BFF routes. `ledger` is untouched.
- Dependencies: none new in the backend; SMS is HTTP behind an adapter (ADR 0009). The web adds no runtime dependency.
- Data: PostgreSQL. New tables `users`, `user_sessions`, `otp_challenges`, `goal_events`; `goals` gains creator, destination and threshold columns. Migration `V3__organizer_identity.sql` (V1/V2 are already applied).
- Time/randomness: `Clock` for expiry, codes, slug and session tokens are injectable so tests are deterministic.

## Design

### Identity module

- `PhoneNormalizer`: local `0XXXXXXXXX` or E.164 `+233...` to E.164; reject anything else.
- `PhoneCipher`: move `PayerPhoneCipher` to `com.groupcollections.security` as a shared `PhoneCipher` (AES-GCM + keyed HMAC lookup hash). Contribution code uses the shared class; the existing environment key `PAYER_PHONE_KEY` is kept.
- `OtpService`:
  - Request: normalize, rate-limit per phone hash and IP, create a challenge with a six-digit code hashed by HMAC-SHA256 (derived key), expiry five minutes, attempts 0/5, and send via `SmsSender`. Always 202 for valid numbers.
  - Verify: find the newest unconsumed unexpired challenge for the phone hash, increment attempts atomically, compare the code in constant time, consume on success, create the user on first success, and issue a session. Failures return 401 `INVALID_OTP`; five failures burn the challenge.
  - Resend: a new request within 60 seconds is refused (`RESEND_TOO_SOON`); otherwise the previous unused challenge is consumed and a new code is sent.
- `SmsSender`: interface; `FakeSmsSender` in tests records codes; a logging sender is for local dev only. The production HTTP adapter is blocked on ADR 0009 acceptance.
- `SessionService`: opaque 32-byte token, SHA-256 hash stored in `user_sessions`, absolute 30-day expiry, rotation on sign-in, revocation on logout. Cookie `tgthr_session` is HttpOnly, Secure (configurable off for local http), SameSite=Lax, Path=/.
- `OrganizerAuthFilter`: resolves the cookie for `/organizer/**`, loads the session and user, returns 401 Problem when absent/expired/revoked.
- `SameOriginFilter`: for state-changing `/organizer/**` and `/auth/logout`, rejects a request whose `Origin` is present and not the configured web origin, or whose `Sec-Fetch-Site` is `cross-site`, with 403.
- Rate limits: extend the existing limiter into a scoped helper (OTP per phone/IP, goal creation per organizer). Limits are configuration.

### Goal creation

- `GoalDraft` request: title, template, target type, fixed/target/minimum amounts, deadline, destination type, destination phone and network, second approver rule and threshold, hold window, surplus rule.
- Validation: template/type/status enums, positive amounts, fixed requires fixed amount, open minimum ≥ 1,000 pesewas, hold window 0–1440 minutes, threshold ≥ 0. Invalid input returns 422 and creates nothing.
- Slug: 10 cryptographically random bytes, Crockford/base32 lower-case (16 chars), unique; retry up to three times on collision.
- Destination: `AccountResolver` interface. Production adapter calls the Paystack account-resolution endpoint for the MoMo network; tests use a fake resolver. Resolved name is stored and the destination is locked. `ADMIN_MOMO` requires a legal name on the user and a normalized name match; `BENEFICIARY_MOMO` requires a resolved name. Unresolvable/mismatched → 422 `DESTINATION_UNVERIFIED`; partner outage → 503 `DESTINATION_CHECK_UNAVAILABLE`.
- Idempotency: `Idempotency-Key` required. `goals` stores the key; unique `(created_by_user_id, create_idempotency_key)`. A duplicate returns the original goal, never a second row.
- Ownership: create sets `created_by_user_id`; list/read/close filter by it; another organizer gets 404.
- Close: owner-only `OPEN → CLOSED`; append a `goal_events` row; repeat close is a no-op. Contributions already reject non-OPEN goals.

### Schema (`V3__organizer_identity.sql`)

- `users`: `id`, `phone_ciphertext`, `phone_hash` unique, `display_name`, `legal_name`, timestamps.
- `user_sessions`: `id`, `user_id`, `token_hash` unique, `created_at`, `expires_at`, `revoked_at`.
- `otp_challenges`: `id`, `phone_hash`, `code_hash`, `attempts`, `max_attempts`, `expires_at`, `consumed_at`, `created_at`; index `(phone_hash, created_at desc)`.
- `goals` additions: `created_by_user_id` (FK, nullable for pre-existing fixtures), `destination_phone_ciphertext`, `destination_phone_hash`, `destination_network`, `destination_account_name`, `destination_locked_at`, `second_approver_threshold_pesewas` (default 50,000, ≥ 0), `create_idempotency_key`; unique `(created_by_user_id, create_idempotency_key)`; index `created_by_user_id`. A check requires destination fields when a creator is present.
- `goal_events`: `id`, `goal_id`, `event`, `from_status`, `to_status`, `actor_user_id`, `occurred_at`, `detail`; append-only triggers for UPDATE/DELETE/TRUNCATE.
- No ledger tables change.

### API

- `POST /auth/otp/request`, `POST /auth/otp/verify` (sets cookie), `POST /auth/logout`, `GET /organizer/me`.
- `POST /organizer/goals` (`Idempotency-Key`), `GET /organizer/goals`, `GET /organizer/goals/{id}`, `POST /organizer/goals/{id}/close`.
- OpenAPI gains a `cookieAuth` security scheme, the schemas, and 401/403/404/409/422/429/503 Problem responses.
- Public goal responses are unchanged; the destination phone never appears.

### Web

- Routes: `/signin` (phone → code → names), `/organizer` dashboard, `/organizer/goals/new` wizard, `/organizer/goals/[id]` detail with close.
- BFF route handlers under `/api/auth` and `/api/organizer` forward the cookie and `Origin` to the backend and relay `Set-Cookie` back. The API base stays server-side.
- Design: prototype screens 1 (home hub) and 4 (goal wizard); sage/forest palette, cards and mono amounts.
- The sign-in flow collects the account legal name; the goal wizard collects template, title, target, deadline, destination phone/network/type, second approver rule/threshold, hold window and surplus rule; the review card mirrors the public page.

### Main sequence

1. `POST /auth/otp/request` normalizes, rate-limits, stores a challenge and sends the code.
2. `POST /auth/otp/verify` consumes the challenge, creates the user if needed, writes a session and sets the cookie.
3. `POST /organizer/goals` checks the session and origin, validates fields, resolves the destination name, generates the slug, inserts the goal and event in one transaction, and returns 201.
4. A retry with the same key returns the stored goal.
5. `POST /organizer/goals/{id}/close` checks ownership, moves `OPEN → CLOSED`, appends the event; public contribution creation now returns 409.
6. Logout revokes the session row and clears the cookie.

## Test strategy

| Criterion/invariant | Level | Test class | Notes |
| --- | --- | --- | --- |
| AC-ORG-001 | Integration | `OrganizerAuthIT` | Fake SMS sender receives one code; always 202 |
| AC-ORG-002 | Integration | `OrganizerAuthIT` | Cookie attributes, rotation, `/organizer/me` |
| AC-ORG-003 | Integration | `OrganizerAuthIT` | Wrong, expired, reused, over-attempted |
| AC-ORG-004 | Integration | `OrganizerAuthIT` | Per-phone and per-IP 429 |
| AC-ORG-005 | Integration | `OrganizerGoalIT` | 201, slug, public page, no ledger |
| AC-ORG-006 | Integration | `OrganizerGoalIT` | Fixed/open amount rules |
| AC-ORG-007 | Integration | `OrganizerGoalIT` | Fake resolver match/mismatch/outage |
| AC-ORG-008 | Integration | `OrganizerGoalIT` | Idempotency key returns the original |
| AC-ORG-009 | Integration | `OrganizerGoalIT` | Own goals only; 404 for others |
| AC-ORG-010 | Integration | `OrganizerAuthIT` | Missing/expired/revoked session |
| AC-ORG-011 | Integration | `OrganizerAuthIT` | Cross-site Origin/Sec-Fetch-Site |
| AC-ORG-012 | Contract | `OrganizerContractTest` | No phone in any body; cipher at rest |
| AC-ORG-013 | Integration | `OrganizerGoalIT` | Close, 409 for contributions, idempotent |
| AC-ORG-014 | Integration | `OrganizerAuthIT` | Logout revokes |
| AC-ORG-015 | Integration | `OrganizerAuthIT` | Resend window and invalidation |
| INV-IDENT-001 | Integration | `OrganizerIdentityInvariantsTest` | Session hashing and revocation |
| INV-IDENT-002 | Integration | `OrganizerIdentityInvariantsTest` | Single-use, expiry, attempts |
| INV-IDENT-003 | Contract | `OrganizerContractTest` | Ciphertext at rest; no phone in logs/responses |

## Risks and mitigations

- **Paystack MoMo account resolution may not be available.** Spike the sandbox endpoint first (T001). If unavailable, stop and amend this plan/spec; do not invent a name-match.
- **SMS provider and deliverability.** ADR 0009 must be accepted before production; tests use the fake. SMS failures return 503 and alert.
- **Cookie through the Next proxy.** Relay `Set-Cookie` and forward `Origin`/`Cookie`; integration-test the BFF and cover it in the web E2E.
- **Self-declared legal name is not KYC.** The name match blocks mismatches, not a determined fraudster; operator KYC remains a later control.
- **In-memory rate limits are per instance.** Acceptable for the first slice; a shared limiter needs an ADR.
- **Open-redirect and CSRF gaps.** Origin checks plus JSON-only endpoints; no cookies on public pages.

## ADRs

- ADR 0009 (SMS provider adapter, Accepted; live sandbox check and production adapter T162).
- ADR 0010 (opaque server-side sessions, Accepted; multi-device, logout revokes the presented session).
