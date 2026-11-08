# ADR 0010: Opaque server-side sessions

- Status: Accepted
- Date: 2026-11-06
- Deciders: Ahmed Zubairu
- Constitution articles touched: VIII

## Context

Organizers sign in on phones, often shared. Sessions must be revocable immediately when the user logs out or an account is frozen, and they must not expose credentials in the browser. Self-contained tokens (JWT) make revocation and theft response harder unless a denylist is added.

## Decision

We will use opaque 256-bit random session tokens stored hashed in PostgreSQL, sent in an HttpOnly, Secure, SameSite=Lax cookie. Sign-in issues a new token for the signing-in device and does not revoke other sessions (multi-device); logout revokes the presented session, and expiry is absolute at 30 days. No session secret is needed beyond the stored hash.

## Consequences

**Good**

- Revocation is immediate and auditable.
- No token payload leaks; a stolen database does not yield usable cookies.
- Fits the existing single database; no new datastore.

**Costs and risks**

- Every protected request reads the session row; an index on the token hash keeps it cheap.
- A shared cookie jar across subdomains needs care; the cookie is scoped to the web origin.

## Alternatives considered

| Option | Why not |
| --- | --- |
| JWT access tokens | Revocation needs a denylist or short expiry; more moving parts |
| Redis sessions | Redis is not the source of truth and adds a failure mode for auth |
| Basic auth | No logout, no rotation, and it trains users to store passwords |
