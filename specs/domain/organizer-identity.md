# Domain spec: Organizer identity

| Field | Value |
| --- | --- |
| Status | Accepted |
| Area code | IDENT |
| Related features | FEAT-003, FEAT-004 |

## Purpose

Organizer accounts, one-time codes and sessions are the authentication boundary for every signed-in action. These invariants hold across features; a future contributor account uses the same rules.

## Terms

| Term | Meaning |
| --- | --- |
| Challenge | A one-time code issued to a normalized phone number, with an expiry and an attempt counter. |
| Session | A server-side record that authorizes a signed-in user until it expires or is revoked. |
| Normalized phone | E.164 form of a Ghana number; the only form used for hashing and lookup. |

## Invariants

| ID | Statement |
| --- | --- |
| INV-IDENT-001 | A protected request is authorized only by a live, unexpired and unrevoked session; session tokens are stored as hashes, never in plaintext. |
| INV-IDENT-002 | A challenge is single-use, expires after its window and is attempt-limited; a used, expired or over-attempted challenge can never create a session. |
| INV-IDENT-003 | Phone numbers are stored encrypted with a keyed lookup hash, are never returned by any endpoint and are never written to logs in plaintext. |

## Resolved decisions

- 2026-11-06: sessions are opaque server-side records in HttpOnly cookies rather than self-contained tokens, so revocation is immediate (ADR 0010).
- 2026-11-06: codes are delivered by SMS through a provider adapter; tests use a fake (ADR 0009).
