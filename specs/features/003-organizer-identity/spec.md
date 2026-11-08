# Feature 003: Organizer identity and goal creation

| Field | Value |
| --- | --- |
| Feature ID | FEAT-003 |
| Status | In progress |
| Owner | Ahmed Zubairu |
| Area code | ORG |
| Created | 2026-11-06 |
| Related | docs/adr/0005-payout-safeguards.md, docs/adr/0009-sms-otp-provider.md, docs/adr/0010-opaque-sessions.md, specs/domain/organizer-identity.md, specs/features/001-contributions/spec.md |

## Problem and outcome

FEAT-001 assumes a goal already exists, but organizers have no account and no way to create one. Anyone could otherwise publish a goal, and a hijacked account could redirect money to an arbitrary MoMo number. When this feature works, an organizer signs in with a phone OTP, creates a goal with a locked and name-matched payout destination and visible safeguards, shares the link, and sees only their own goals. Contributors keep using the public pages from FEAT-001 without signing in.

## Actors

- Organizer: signs in, creates and closes goals, wants their money to reach the right destination.
- Contributor: opens the public link, unaffected by sign-in.
- SMS partner: delivers one-time codes.
- Payment partner: resolves the destination MoMo account name.
- Platform operator: can freeze goals and approve payouts later (FEAT-002).

## User stories

| Priority | Story |
| --- | --- |
| P1 | As an organizer, I sign in with my phone and a one-time SMS code, so only I can create and manage my goals. |
| P1 | As an organizer, I create a goal from a template with amount rules, a deadline and payout safeguards, so contributors see exactly how their money is handled. |
| P1 | As an organizer, I see only my own goals and their raised totals, so I know where things stand. |
| P1 | As the platform, I lock and name-match the payout destination at creation, so a hijacked account cannot silently redirect money. |
| P2 | As an organizer, I close my goal, so no new contributions are accepted. |
| P2 | As an organizer, I sign out, so a shared device does not keep my session. |

## Scope

**In scope**

- Phone OTP sign-in with rate limits, single-use codes, attempt limits and resend rules.
- Opaque server-side sessions in HttpOnly cookies, with logout and expiry.
- Organizer accounts created on first successful verification, with display name and a self-declared legal name.
- Goal creation from a template with title, target type and amounts, deadline, payout destination (MoMo phone, network, resolved account name), destination type, second-approver rule and threshold, hold window and surplus rule.
- Destination account-name resolution through the payment partner and a permanent lock at creation.
- Organizer goal list (own goals only) and goal close.
- Organizer web pages: sign-in, dashboard and goal-creation wizard, using the design prototype as the reference.

**Out of scope**

- Payout requests, approvals and transfer execution (FEAT-002).
- Destination changes after creation; the change flow with a fresh OTP and contributor notification will be specified later.
- Editing a goal after creation; freezing by an operator.
- Claiming contributions, signed-in contributor history (FEAT-004), refunds (FEAT-005).
- SMS notifications other than the sign-in code.
- Card payments, non-Ghana numbers and multiple organizers per goal.

## Rules

1. **Phone format.** Accept a Ghana local number (`0XXXXXXXXX`) or E.164 (`+233XXXXXXXXX`), normalize to E.164, and reject anything else with 422. The same normalized phone always maps to the same account.
2. **Codes.** Six digits, five-minute expiry, single use, at most five verification attempts. A resend is allowed after 60 seconds and invalidates the previous code. Codes are stored hashed, never in plaintext.
3. **Enumeration.** Requesting a code always returns 202, whether or not the phone already has an account. Request and verify are rate-limited per phone and per IP.
4. **Sessions.** Opaque random tokens are stored hashed. The cookie is HttpOnly, Secure, SameSite=Lax, and expires after 30 days. Sign-in rotates the token, logout revokes it, and every organizer request checks the session server-side.
5. **Accounts.** A successful verification creates the account on first use. The organizer supplies a display name and a legal name; the legal name is required before creating a goal whose destination is the organizer's own MoMo (`ADMIN_MOMO`).
6. **Goal fields.** Creation requires a title, template, target type, amounts, payout destination (phone, network, resolved account name), destination type, second-approver rule, hold window and surplus rule. Defaults: hold window 60 minutes, second-approver threshold GHS 500, surplus `PAY_BENEFICIARY`. The slug is unguessable, at least 12 characters, and unique.
7. **Target rules.** `FIXED` requires a positive fixed amount. `OPEN` requires a minimum at least GHS 10 (1,000 pesewas). The target amount is optional and positive. Invalid combinations return 422 and create no goal.
8. **Destination match.** The payment partner resolves the destination MoMo account name. For `ADMIN_MOMO`, the resolved name must match the organizer's legal name after normalizing case and spacing. For `BENEFICIARY_MOMO`, the resolved name must exist and is stored on the goal. A mismatch or an unresolvable account returns 422 `DESTINATION_UNVERIFIED` and creates no goal; a partner outage returns 503 and creates no goal. The destination is immutable in this slice.
9. **Idempotency.** Goal creation requires an `Idempotency-Key`, unique per organizer. A retry returns the original goal and never creates a second one.
10. **Ownership.** An organizer can list, read and close only their own goals. Another organizer receives 404.
11. **Close.** Only the owner closes an `OPEN` goal; it becomes `CLOSED`, contributions return 409, and closing again is a no-op. `FROZEN` is operator-only.
12. **Cross-site protection.** State-changing organizer endpoints reject requests whose `Origin` or `Sec-Fetch-Site` shows another site.
13. **Privacy.** Neither public nor organizer responses return a phone number. Organizer and destination phones are encrypted at rest with a keyed lookup hash and never written to logs.
14. **Ledger.** Goal creation posts no ledger entries and stores no balance.

## Resolved decisions

| Date | Question | Decision |
| --- | --- | --- |
| 2026-11-06 | How are sign-in codes delivered? | SMS through a provider adapter; the vendor is chosen in ADR 0009. Tests use the fake sender. |
| 2026-11-06 | How is an `ADMIN_MOMO` destination verified? | The organizer declares a legal name; the partner-resolved MoMo name must match it. Operator KYC can come later. |
| 2026-11-06 | What can an organizer do in this slice? | Sign in, create, list and close goals. Destination changes and editing come later. |
| 2026-11-06 | Session model | Opaque server-side sessions in cookies (ADR 0010). |
| 2026-11-06 | Timing defaults | Code 6 digits / 5 minutes / 5 attempts / 60-second resend; session 30 days. |

## Acceptance criteria

| ID | Priority | Given / When / Then | Test level |
| --- | --- | --- | --- |
| AC-ORG-001 | P1 | Given a valid Ghana phone number, when a code is requested, then the response is 202, exactly one code is sent through the SMS sender, and no session or account is revealed. | Integration |
| AC-ORG-002 | P1 | Given a live, unused code, when the organizer verifies it, then a session is created with an HttpOnly, Secure, SameSite=Lax cookie, the token is rotated, and `GET /organizer/me` returns the organizer. | Integration |
| AC-ORG-003 | P1 | Given an incorrect, expired, reused or over-attempted code, when it is verified, then the response is 401 or 422 `INVALID_OTP`, no session exists, and the challenge is burned after the attempt limit. | Integration |
| AC-ORG-004 | P1 | Given too many requests for one phone or IP, when another code request or verification arrives, then the response is 429 `RATE_LIMITED` and no code or session is created. | Integration |
| AC-ORG-005 | P1 | Given an authenticated organizer, when they submit valid goal fields, then the response is 201 with a unique unguessable slug, the public goal page is reachable, the payout rules and destination are stored, and no ledger entry exists. | Integration |
| AC-ORG-006 | P1 | Given invalid target fields, when a goal is created, then the response is 422 and no goal exists; a fixed goal requires its fixed amount and an open goal requires a minimum of at least 1,000 pesewas. | Integration |
| AC-ORG-007 | P1 | Given a destination MoMo number, when it cannot be resolved or an `ADMIN_MOMO` name does not match the organizer's legal name, then the response is 422 `DESTINATION_UNVERIFIED` and no goal exists; a partner outage is 503 and creates nothing. | Integration |
| AC-ORG-008 | P1 | Given a create request retried with the same organizer `Idempotency-Key`, when it is received again, then the original goal is returned and no second goal exists. | Integration |
| AC-ORG-009 | P1 | Given an organizer with goals, when they list or read their goals, then only their own goals with raised totals and status are returned; another organizer receives 404. | Integration |
| AC-ORG-010 | P1 | Given no session or an expired or revoked session, when an organizer endpoint is called, then the response is 401 and nothing is created or changed. | Integration |
| AC-ORG-011 | P1 | Given a state-changing organizer request from another origin, when it arrives, then the response is 403 and nothing changes; a same-origin request succeeds. | Integration |
| AC-ORG-012 | P1 | Given any public or organizer response, when it is read, then it contains no phone number; organizer and destination phones are encrypted at rest and looked up by keyed hash. | Contract |
| AC-ORG-013 | P2 | Given an owner and an `OPEN` goal, when the owner closes it, then it becomes `CLOSED`, contributions return 409, and a second close is a no-op; a non-owner receives 404. | Integration |
| AC-ORG-014 | P2 | Given a live session, when the organizer logs out, then the session is revoked and subsequent organizer calls return 401. | Integration |
| AC-ORG-015 | P2 | Given a code issued less than 60 seconds ago, when a resend is requested, then it is refused; after 60 seconds a new code is sent and the previous code no longer works. | Integration |

## Failure modes and edge cases

| Situation | Expected behavior | Criterion |
| --- | --- | --- |
| SMS partner rejects or times out | 202 is not returned; the request returns 503 `SMS_UNAVAILABLE`, no usable code is stored, and the failure is alerted | gap: needs a criterion |
| Destination partner times out during name resolution | 503 `DESTINATION_CHECK_UNAVAILABLE`; no goal; alert | AC-ORG-007 |
| Duplicate goal title or slug collision | Slug collision is retried; duplicate titles are allowed | AC-ORG-005 |
| Phone with `+233`, spaces or a foreign country code | Normalized or rejected with 422 | AC-ORG-001 |
| OTP brute force | Attempt limit on the challenge plus per-phone/IP rate limits | AC-ORG-003/004 |
| Cross-site form or fetch | 403 and no change | AC-ORG-011 |
| Stolen session token | HttpOnly/Secure/SameSite, hashed token, rotation on sign-in, logout revocation | AC-ORG-002/014 |
| Organizer requests another organizer's goal | 404, no existence leak | AC-ORG-009 |
| Legal name missing for `ADMIN_MOMO` | 422 `LEGAL_NAME_REQUIRED`, no goal | AC-ORG-007 |
| Goal creation retried after a network failure | Same `Idempotency-Key` returns the original | AC-ORG-008 |

## Data and ledger impact

- Entities created: organizer account, organizer session, OTP challenge, goal destination and creator fields, second-approver threshold.
- Ledger entries posted: none on creation. Payout postings arrive with FEAT-002.
- Invariants touched: INV-IDENT-001, INV-IDENT-002, INV-IDENT-003; Constitution V.1 (destination lock), V.5 (public safeguards).
- `INV-LEDGER-003` remains with FEAT-002.

## API impact

- Added, under the existing `/v1` context:
  - `POST /auth/otp/request`
  - `POST /auth/otp/verify` (sets the session cookie)
  - `POST /auth/logout`
  - `GET /organizer/me`
  - `POST /organizer/goals` (requires `Idempotency-Key`)
  - `GET /organizer/goals`
  - `GET /organizer/goals/{id}`
  - `POST /organizer/goals/{id}/close`
- `specs/api/openapi.yaml` gains the cookie security scheme, request and response schemas, and 401/403/404/409/422/429/503 responses.
- The public goal contract gains the second-approver threshold (money-affecting safeguard); the destination phone is never public.
- Backward compatible; no existing endpoint changes.

## Security, privacy and fraud

- OTP brute force, replay and enumeration; SMS pumping (per-phone/IP limits and provider spend alerts).
- Session theft and fixation; cross-site requests; token rotation and revocation.
- Phone numbers encrypted at rest and redacted from logs; no phone in responses.
- Destination hijack: name match through the partner plus an immutable destination at creation.
- Cross-tenant reads and writes: ownership checks and 404 responses.
- SMS provider credentials come from the environment; no secrets in the repository.
- Goals are unguessable because the slug is random, not sequential.

## Observability

- Events: code requested, code verified, verification failed, session created or revoked, goal created, goal closed, destination resolved or failed.
- Metrics: OTP requests and outcomes, active sessions, goals created, destination resolution latency and failures.
- Alerts: SMS failure rate, OTP failure spike, destination mismatch, partner resolution outage.

## Non-functional requirements

- The sign-in and goal-creation pages work in WhatsApp's in-app browser on a phone.
- Organizer endpoints respond within 500 ms at the 95th percentile, excluding the partner SMS and name-resolution calls.
- Goal creation is safe to retry with the same idempotency key.
