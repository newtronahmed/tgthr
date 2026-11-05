# ADR 0004: Idempotency enforced by the database

- Status: Accepted
- Date: 2026-11-04
- Deciders: Ahmed Zubairu
- Constitution articles touched: IV

## Context

Payment partners repeat, delay and reorder callbacks, and clients retry requests. A duplicate must never post money twice. Redis locks can expire, be lost on restart or race under failover.

## Decision

We will enforce idempotency with unique constraints in PostgreSQL: on the provider reference for contributions and on the idempotency key for ledger postings and client retries. Redis may be used to answer duplicates faster but is never what makes them safe.

## Consequences

**Good**

- Correctness survives cache loss and process restarts.
- Behavior is testable with Testcontainers against a real database.

**Costs and risks**

- A duplicate hits the database before being rejected; acceptable at expected volume.
- Handlers must treat a unique-violation as "already processed", not as an error.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Redis locks as the guarantee | Expiry and failure modes can double-post |
| Application-level check then insert | Race between check and insert |
