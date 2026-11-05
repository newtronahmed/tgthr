# ADR 0002: Append-only double-entry ledger

- Status: Accepted
- Date: 2026-11-04
- Deciders: Ahmed Zubairu
- Constitution articles touched: I, IV

## Context

People give money toward a goal and expect it to be accounted for exactly. Disputes, refunds and partner reconciliation all need a trustworthy history of what happened and when.

## Decision

We will record every money movement as an immutable ledger entry in double-entry form, store amounts as integer pesewas, and derive all balances from entries. Corrections are new reversing entries. Database permissions reject UPDATE and DELETE on ledger tables.

## Consequences

**Good**

- Complete audit trail; balances can be recomputed and compared with the partner.
- Property tests can prove accounting invariants.

**Costs and risks**

- Reads that need balances compute them; add indexes or materialized views only when measurements require it, never by storing editable balances.
- Mistakes are corrected by adding entries, which needs operator tooling and discipline.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Stored balance columns | Can drift from history and hide bugs |
| Single-entry transaction log | Cannot prove entries net to zero |
