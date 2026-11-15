# ADR 0019: Dedicated platform-revenue account

- Status: Accepted
- Date: 2026-11-15
- Deciders: Ahmed Zubairu
- Constitution articles touched: I (money is exact and traceable), II (spec first), IV (idempotent and consistent)

## Context

ADR 0013 resolves an aged suspense payment that nobody claims as miscellaneous income: debit
`SUSPENSE`, credit "platform revenue". The ledger has no such account. The only revenue-shaped
account is `PLATFORM_FEE`, which records the 1.5% service fee from ADR 0007 and is read directly as
platform service-fee revenue. Posting miscellaneous income there would merge two different revenue
sources and destroy that read.

FEAT-005 needs the account, its symbol and its migration fixed before the suspense slice writes
code. `V5__currency_and_settlement.sql` enforces `ledger_entries.account` with a closed check list
that does not contain a revenue account, so the account cannot be used without a migration.
`LedgerAccount` mirrors that list in Java.

## Decision

Add a dedicated account `PLATFORM_REVENUE` and keep it separate from `PLATFORM_FEE`.

- Migration `V17__platform_revenue_account.sql` drops and re-adds
  `ledger_entries_account_check` with `PLATFORM_REVENUE` added to the existing list. It rewrites no
  rows and adds no entry type; `ADJUSTMENT` already exists.
- `LedgerAccount` gains `PLATFORM_REVENUE`; `LedgerEntryType` is unchanged.
- Miscellaneous income from suspense posts one `ADJUSTMENT` set: debit `SUSPENSE`, credit
  `PLATFORM_REVENUE`. It needs no `BANK` entry, because the ledger records obligations, not cash
  movement (ADR 0012).
- The account symbol and migration number are fixed by this ADR. Later changes to either need a new
  ADR.
- The payment stays flagged for reconciliation after income is recorded; only a reclassification
  clears the flag, so income never enters goal totals or the public feed.

## Consequences

**Good**

- Service-fee revenue (`PLATFORM_FEE`) and miscellaneous income (`PLATFORM_REVENUE`) stay separately
  auditable and separately reportable.
- The migration is a constraint swap with no data rewrite, so it is safe on a live table.
- The Java enum and the database check stay in step, and no new entry type expands the surface.

**Costs and risks**

- One more account and one more migration for an event that may stay rare.
- Reports that want total platform revenue must add both accounts; any single-account report is
  incomplete by design.
- `PLATFORM_REVENUE` credits are income recognition, not cash. A future settlement-posting ADR must
  reconcile them against the partner and bank accounts.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Post income to `PLATFORM_FEE` | Mixes service-fee revenue with miscellaneous income and breaks the existing revenue read. |
| Post income to `BANK` | No cash movement is recorded by this ledger yet; a `BANK` credit would misstate cash and could go negative. |
| Add a new `INCOME` entry type | `ADJUSTMENT` already means an operator correction paired with a reason, and a new type would widen every entry-type check for no gain. |
| A separate revenue table outside the ledger | Splits money state across two stores, violating the single-ledger rule (Constitution Article I). |
