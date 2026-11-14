# ADR 0017: Operator identity and MFA

- Status: Accepted
- Date: 2026-11-14
- Deciders: Ahmed Zubairu
- Constitution articles touched: V (trust is a feature), VIII (security and privacy), IX (observable)

## Context

FEAT-005 introduces actions that move money and change ledger state: refunds, suspense
reclassification, miscellaneous income, and later loss allocation. These actions need an actor who is
accountable and strongly authenticated. Reusing the organizer identity would give every organizer
operator powers, and its 30-day phone-OTP session is too long-lived for money movement. A shared API
key or CLI token would authenticate a tool, not a person, and would make the audit trail ambiguous.

SSO is not available in the current deployment and would block the refund slice. TOTP is available
without a new identity provider and can be replaced later without changing the authorization model.

## Decision

We will give each operator a **separate, per-person identity** on a dedicated operator surface. It
will use a unique username and password plus TOTP as a second factor. Organizer accounts, phone OTP
and organizer sessions are never accepted for operator actions, and operator sessions are never
accepted for organizer actions.

### Authentication

- Passwords are at least 12 characters and stored with a memory-hard KDF: Argon2id, or
  PBKDF2-HMAC-SHA256 with at least 600,000 iterations if no Argon2 implementation is available.
  Passwords, TOTP secrets and recovery codes never appear in logs or audit payloads.
- TOTP follows RFC 6238: SHA-1, 6 digits, 30-second step, one step of clock skew, and a consumed step
  cannot be replayed. The TOTP secret is encrypted at rest with `OPERATOR_TOTP_ENCRYPTION_KEY`, a
  required environment variable with no default. Startup fails when operator auth is enabled and the
  key is missing.
- Ten single-use recovery codes are generated at enrollment, stored hashed, and regenerating them
  invalidates the old set.
- An administrator creates the account and issues a one-time enrollment token. First sign-in forces a
  password change and TOTP enrollment before any role becomes effective.
- The first `OPERATOR_ADMIN` is provisioned at deploy time by a CLI or one-shot runner using a
  one-time token. Migrations never contain a password, TOTP secret, recovery code or token.
- Sessions are opaque and server-side: 8-hour absolute lifetime, 30-minute idle timeout, `HttpOnly`,
  `Secure`, `SameSite=Strict`, a cookie name distinct from the organizer session, and server-side
  revocation on logout. The session id rotates after password and TOTP verification.
- Five failed password or TOTP attempts in 15 minutes lock the account for 15 minutes and raise an
  alert. Errors are generic to avoid account enumeration.
- There is no public operator registration and no email-based password reset in P1. An administrator
  resets the account with a one-time token and forces re-enrollment.
- Privileged approvals (income, loss) and role changes require a fresh TOTP check within the previous
  five minutes (step-up).

### Roles and dual control

| Role | May do |
| --- | --- |
| `REFUND_OPERATOR` | Request and read pre-payout refunds; read the reconciliation queue. |
| `RECONCILER` | Resolve suspense items by refund or reclassification; propose miscellaneous income. |
| `FINANCE_APPROVER` | Approve miscellaneous income and loss allocation; read finance reports. |
| `OPERATOR_ADMIN` | Create/disable operator accounts and assign roles. Cannot approve money movements alone. |

- A pre-payout refund requires one `REFUND_OPERATOR`, a reason and an idempotency key; it returns
  money only to the original payer and is not a new destination.
- Miscellaneous income (P1) and loss allocation (P2) require two distinct people: a proposer and a
  different `FINANCE_APPROVER`. The same person can never fill both roles for one action.
- Reclassification requires a `RECONCILER` and a reason; a second approver is recommended but not
  required in P1.
- Role assignment is itself audited. Removing a role or disabling an account revokes its sessions.

### Audit and operations

- Every login success/failure, MFA result, role change, and operator action is appended to an
  immutable audit table with actor id, action, reason, request id, and before/after state. Phone
  numbers are never recorded.
- Operator auth failures, lockouts and unusual action volume are metrics with alerts.
- Operator sessions cannot create a payout or change a payout destination.

### Deferred

SSO/OIDC is deferred. When it arrives, a new ADR may replace password authentication, but TOTP stays
until the identity provider enforces MFA and the operator flow is re-reviewed.

## Consequences

**Good**

- Every money movement is attributable to a named person with a strong second factor.
- Compromising an organizer session or a shared secret does not yield operator powers.
- Short sessions, step-up and dual control bound the damage of a stolen operator session.
- The authorization model (roles and dual control) survives a later move to SSO.

**Costs and risks**

- Operators carry a second credential and enrollment step; account recovery is manual in P1.
- The backend must add password hashing, TOTP, recovery codes, lockout and audit storage.
- A lost TOTP device needs an administrator reset; the runbook must cover it.
- TOTP is phishing-resistant only with care; step-up and short sessions reduce the window.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Reuse the organizer identity with an operator role | Over-privileges every organizer; 30-day phone-OTP sessions are too weak and long-lived for money movement. |
| Shared operator API key or CLI token | Authenticates a tool, not a person; no per-person audit, revocation or dual control. |
| SSO/OIDC now | No identity provider is available; would block the refund slice without changing the role model. |
| Email magic links only | Adds an email dependency and gives no second factor for money movement. |
| SMS OTP for operators | SIM-swap risk, per-message cost, and the sender is not approved for live traffic yet. |
