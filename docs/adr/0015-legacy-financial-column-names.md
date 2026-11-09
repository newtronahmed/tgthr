# ADR 0015: Legacy financial column names for the MVP

- Status: Proposed
- Date: 2026-11-08
- Deciders: Ahmed Zubairu
- Constitution articles touched: I (money is exact)

## Context

ADR 0011's example schema renamed existing financial columns to `*_minor_units`. The Phase 1
implementation adds `currency_code` and uses integer minor units, but keeps the existing
`amount_pesewas`, `processing_fee_pesewas`, `total_pesewas` and related column names on goals,
contributions and ledger entries. For GHS those names are accurate; for any future non-GHS currency
they would be misleading.

## Decision

Retain the legacy `_pesewas` column names for the GHS-only MVP. `currency_code` is authoritative;
every value is an integer minor unit in that currency. The names carry no cross-currency guarantee.
A future rename to `*_minor_units` will be a separate expand/contract migration when a second
currency is supported.

## Consequences

**Good:** no risky rename in the current migration; existing queries/tests remain stable.

**Costs:** the naming is misleading for non-GHS; ADR 0011's schema example is not implemented
literally. This ADR must be accepted (or superseded by a rename) before multi-currency work.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Rename now | Large mechanical change with a rolling-deploy hazard before any non-GHS demand |
| Ignore ADR 0011 | Leaves a silent spec/code contradiction |
