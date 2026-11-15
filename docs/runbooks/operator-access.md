# Operator identity and recovery runbook (T495)

**Audience:** `OPERATOR_ADMIN` and the on-call engineer who holds deploy access.
**Scope:** first-admin bootstrap, enrollment, sign-in, MFA/recovery, lockout, role changes, disable
and key rotation.
**Rule:** operator accounts are per person. Never share a login, TOTP secret or recovery code. Never
paste a secret or code into a ticket, chat or log. Every privileged action is audited.

## Configuration

| Variable | Default | Meaning |
| --- | --- | --- |
| `OPERATOR_ENABLED` | `false` | Operator identity and `/operator/**` |
| `OPERATOR_TOTP_ENCRYPTION_KEY` | none | Base64 32-byte AES-GCM key for TOTP secrets; startup fails when missing |
| `OPERATOR_SESSION_TTL` | `PT8H` | Absolute session lifetime |
| `OPERATOR_IDLE_TIMEOUT` | `PT30M` | Idle lifetime |
| `OPERATOR_MFA_TTL` | `PT5M` | Password-challenge lifetime |
| `OPERATOR_MAX_FAILED_ATTEMPTS` | `5` | Attempts before lockout |
| `OPERATOR_LOCKOUT` | `PT15M` | Lockout duration |
| `OPERATOR_STEP_UP_WINDOW` | `PT5M` | Fresh-TOTP window for sensitive actions |
| `OPERATOR_ENROLLMENT_TTL` | `PT24H` | One-time enrollment lifetime |
| `OPERATOR_BOOTSTRAP_TOKEN` | empty | Deploy-time one-time bootstrap token |
| `OPERATOR_BOOTSTRAP_USERNAME` | empty | First admin username |
| `OPERATOR_BOOTSTRAP_TOKEN_FILE` | empty | File that receives the one-time enrollment token |

The `operators` table never stores a plaintext password, TOTP secret, recovery code or token. The
bootstrap token is written to `OPERATOR_BOOTSTRAP_TOKEN_FILE` with `0600` inside the bootstrap
transaction; no migration contains a secret.

## Roles

| Role | May do |
| --- | --- |
| `REFUND_OPERATOR` | Request/read/verify pre-payout refunds; read the reconciliation queue |
| `RECONCILER` | Resolve suspense by refund or reclassification; propose miscellaneous income |
| `FINANCE_APPROVER` | Approve miscellaneous income and loss allocation; read finance reports |
| `OPERATOR_ADMIN` | Create, disable, enable and change roles; cannot approve money movements alone |

Income needs a `RECONCILER` proposer and a different `FINANCE_APPROVER` approver. The same person can
never fill both roles for one action.

## 1. Bootstrap the first admin

1. Generate a 32-byte base64 TOTP key and set `OPERATOR_TOTP_ENCRYPTION_KEY`. Keep it in the secret
   store; do not reuse `PAYER_PHONE_KEY`.
2. Set `OPERATOR_ENABLED=true`, `OPERATOR_BOOTSTRAP_TOKEN` (short random value),
   `OPERATOR_BOOTSTRAP_USERNAME`, and `OPERATOR_BOOTSTRAP_TOKEN_FILE` to a mounted secret path.
3. Start the application once. If no operator exists, bootstrap creates an `OPERATOR_ADMIN` in
   `PENDING_ENROLLMENT` and writes `OPERATOR_BOOTSTRAP_ENROLLMENT_TOKEN=...` to the file.
4. Read the token from the file, enroll immediately, then remove the file and clear
   `OPERATOR_BOOTSTRAP_TOKEN*` from the environment.
5. Bootstrap is a no-op once any operator exists. It is not a password reset mechanism.

## 2. Enroll an account

An admin creates the account (admin TOTP required):

```http
POST /operator/operators
Cookie: tgthr_operator_session=...
X-Operator-TOTP: <fresh code, if prompted>
Content-Type: application/json

{"username": "ama.refunds", "roles": ["REFUND_OPERATOR"]}
```

The response contains a one-time `enrollmentToken`. Deliver it out of band. The operator then:

```http
POST /operator/auth/enroll
{"token": "<one-time token>", "password": "<12+ characters>"}
```

That returns the TOTP secret, `otpauth://` URI and recovery codes **once**. Store them in an
authenticator and a sealed recovery location, then confirm:

```http
POST /operator/auth/enroll/confirm
{"token": "<one-time token>", "code": "<6-digit code>"}
```

Enrollment tokens are single-use and expire after `OPERATOR_ENROLLMENT_TTL`.

## 3. Sign in

1. `POST /operator/auth/login` with username and password returns a short-lived `challengeToken`.
2. `POST /operator/auth/mfa` with the challenge and the current TOTP code sets the
   `tgthr_operator_session` cookie (`HttpOnly`, `Secure`, `SameSite=Strict`, path `/operator`).
3. `POST /operator/auth/logout` revokes the session.
4. `GET /operator/me` returns the username and roles; it never returns a phone, hash or secret.

A TOTP code can be used once. A replayed code does not start a second session.

## 4. Lost TOTP device or recovery codes

**One recovery code may authorize exactly one step-up.** A recovery code cannot replace a login when
the TOTP device is lost; use `POST /operator/auth/recovery` with username, password and one unused
recovery code to start a session.

1. Use a recovery code to sign in.
2. If the TOTP device is lost, there is no self-service re-enrollment endpoint in P1. Ask an
   `OPERATOR_ADMIN` to disable the account and create a replacement account with a new enrollment
   token. Keep the same person and roles; do not reuse the old username.
3. Record the disable/create actions and the reason in the incident ticket. Never create a shared
   account.

Recovery codes are shown once at enrollment. Consumed codes stay consumed; a used code cannot be
replayed for a second step-up.

## 5. Lockout

Five failed password or TOTP attempts within the lockout window lock the account for
`OPERATOR_LOCKOUT`. Errors stay generic to prevent account enumeration. The lock clears automatically.
Do not raise `OPERATOR_MAX_FAILED_ATTEMPTS` to bypass an incident; investigate the source and reset
only after the lockout expires.

Audit:

```sql
select event, reason, created_at from operator_auth_events
where operator_id = '<operator-id>' order by id desc limit 50;

select action, subject_type, subject_id, reason, before_state, after_state, created_at
from operator_actions where operator_id = '<operator-id>' order by id desc limit 50;
```

## 6. Disable, enable and change roles

All three actions require `OPERATOR_ADMIN` and a fresh TOTP or one recovery code:

```http
PUT  /operator/operators/{id}/roles   {"roles": ["RECONCILER"]}
POST /operator/operators/{id}/disable
POST /operator/operators/{id}/enable
```

- Removing a role or disabling an account revokes its sessions.
- Disabling the last active admin is refused; create or enable another admin first.
- Role changes are audited with the actor, target, before/after roles and reason.
- An admin cannot approve income or loss alone; role membership does not bypass dual control.

## 7. Key rotation

`OPERATOR_TOTP_ENCRYPTION_KEY` encrypts every TOTP secret. Rotating it without re-enrollment makes
existing TOTP secrets undecryptable. The safe sequence is:

1. Create replacement accounts with new enrollment tokens while the old accounts still work.
2. Move roles to the replacements and disable the old accounts.
3. Rotate the key and retire the old secret.

There is no in-place re-encryption endpoint in P1. Do not rotate the key during an incident with
pending money actions.

## 8. Sensitive-action checklist

| Action | Role | Step-up | Dual control |
| --- | --- | --- | --- |
| Request/verify a refund | `REFUND_OPERATOR` | No | No |
| Reclassify or refund suspense | `RECONCILER` | No | No |
| Propose miscellaneous income | `RECONCILER` | No | Proposer recorded |
| Approve miscellaneous income | `FINANCE_APPROVER` | Yes | Different person from the proposer |
| Create/disable/enable an operator | `OPERATOR_ADMIN` | Yes | No money movement |
| Change roles | `OPERATOR_ADMIN` | Yes | No money movement |

When a step-up is required, supply `X-Operator-TOTP: <current code>` or one unused
`X-Operator-Recovery-Code`. A stale session without a fresh code returns `403 STEP_UP_REQUIRED` and
makes no state or ledger change.
