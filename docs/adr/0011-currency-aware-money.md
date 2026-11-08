# ADR 0011: Currency-aware Money type

- Status: Accepted
- Date: 2026-11-08
- Deciders: Ahmed Zubairu
- Constitution articles touched: I (money is exact and traceable)

## Context

The current `Money` record uses a class constant `CURRENCY = "GHS"` and stores only `long pesewas`. Future expansion to Nigeria (NGN), diaspora USD goals, or multi-currency reconciliation would require a breaking change to every financial table and value object. The MVP is Ghana-only, but avoiding a later costly migration is prudent.

## Decision

We will make `Money` a currency-aware value object with `long minorUnits` (replaces `pesewas`) and `String currencyCode` (ISO 4217, uppercase). GHS remains the only supported currency for the MVP; multi-currency transactions and FX conversion remain out of scope.

Database schema: all financial columns gain a `currency_code` column (e.g., `amount_pesewas` becomes `amount_minor_units BIGINT, currency_code TEXT NOT NULL DEFAULT 'GHS'`). Existing rows are backfilled with `GHS` in the same migration.

The `Money` constructor validates that `currencyCode` is non-null, uppercase, and 3 characters. Arithmetic operations (`plus`) require matching currencies and throw `IllegalArgumentException` if mismatched. The `CURRENCY` constant is removed.

## Consequences

**Good**

- Future currency expansion is a feature addition (new supported currency), not a data migration and value-object redesign.
- Ledger entries explicitly record currency, preventing silent cross-currency bugs.
- FX rates, if needed later, can be modeled as separate ledger entries (GHS → USD, record rate and timestamp).

**Costs and risks**

- Every `Money.ofPesewas(amount)` call becomes `Money.of(amount, "GHS")` — one-time find/replace in FEAT-001/003 code.
- Schema migration adds nullable columns with backfill; must run before FEAT-002 to avoid payout-module debt.
- Validating currency in arithmetic adds a branch; performance impact is negligible (single equality check).

## Alternatives considered

| Option | Why not |
| --- | --- |
| Keep GHS implicit, defer until multi-currency | Forces a breaking migration when Nigeria or USD launch; technical debt compounds |
| Store currency per goal, not per amount | Cannot handle future cross-currency contributions (diaspora sending USD to GHS goal) |
| Use separate Money classes per currency | Type safety benefit does not outweigh maintenance cost of parallel classes |
