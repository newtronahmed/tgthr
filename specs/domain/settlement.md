# Domain spec: Settlement

| Field | Value |
| --- | --- |
| Status | Accepted |
| Area code | SETTLE |
| Related features | FEAT-001, FEAT-002 |

## Purpose

Settlement tracks when the payment partner transfers collected funds to the platform's account. A confirmed contribution is a receivable; settlement converts it to cash. Payout eligibility depends on settled funds, not merely confirmed obligations.

## Terms

| Term | Meaning |
| --- | --- |
| Settlement batch | A group of contributions and adjustments settled together by the provider on a specific date, with a provider reference. |
| Settlement item | One line in a settlement batch: a contribution, adjustment, or reversal with gross amount, fee, and net amount. |
| Settled balance | The signed goal-owned amount from `SETTLED` batches, excluding fees and suspended contributions, capped by the goal pool ledger balance. This is the cash available for payout. |
| Pending settlement | A settlement batch created but not yet confirmed as received; contributions in it are not yet eligible for payout. |

## Settlement lifecycle

| State | Meaning | Terminal |
| --- | --- | --- |
| PENDING | Settlement batch created from a provider report or API; cash receipt not yet confirmed; items may be added | No |
| SETTLED | Signed item totals matched the batch and cash was confirmed; goal-owned amounts become payout-eligible | Yes |
| FAILED | Batch rejected or reversed; a later batch may retry its contributions | Yes |

Batches and items carry a `CREDIT`/`DEBIT` direction. Gross, fee and net amounts remain positive
absolute values; the direction gives the sign. Every line also carries a goal-owned amount (zero for
non-goal adjustments).

Item types: `CONTRIBUTION` (requires a contribution and a goal amount), `ADJUSTMENT` (may have no
contribution and a zero goal amount), `REVERSAL` and `CHARGEBACK` (debit direction, with a goal
amount when attributable to a goal).

Settlement tracking does not post ledger entries (ADR 0012). The ADR 0007 FEE/BANK clearing
postings remain deferred until a settlement-posting ADR; the ledger continues to track obligations.

A settlement batch moves `PENDING → SETTLED` when the operator or automated job confirms cash receipt (e.g., bank statement match). A settlement batch moves `PENDING → FAILED` if the provider reverses or cancels it (rare, but possible for fraud or errors).

## Reconciliation

| Scenario | Action |
| --- | --- |
| Contribution in our DB but not in provider settlement | Alert raised; operator investigates (provider delay, dispute, or our missing callback) |
| Settlement item not matching any contribution | `settlement_item` created with `contribution_id = NULL`; alert raised; operator investigates (provider adjustment, chargeback, or unmatched reference) |
| Net amount mismatch | Alert raised with expected vs. actual; operator records adjustment entry if material |

## Invariants

| ID | Statement |
| --- | --- |
| INV-SETTLE-001 | A payout cannot exceed `min(goal pool ledger balance, max(0, signed settled goal amount))`, where the settled goal amount excludes fees and `requires_reconciliation` contributions. |
| INV-SETTLE-002 | Every settlement item with a non-null `contribution_id` references an existing contribution; orphan items raise alerts. |
| INV-SETTLE-003 | Signed settlement items sum to the batch's gross, fees and net totals; `markSettled` refuses a mismatch, raises an alert and requires operator reconciliation. |
| INV-SETTLE-004 | A contribution has at most one `CONTRIBUTION` item in non-`FAILED` batches; duplicates raise an alert and are rejected; a `FAILED` batch may be retried. Settlement items are append-only. |

## Resolved decisions

- 2026-11-08: Settlement tracking via separate tables, not contribution status enum (ADR 0012). Payout eligibility checks settled balance, not confirmed balance.
- 2026-11-08: Settlement ingestion is manual (CSV upload) for MVP; automated Paystack API polling is post-launch enhancement.
- 2026-11-08: Settlement lines are directional; eligibility is goal-owned and excludes fees/suspense (ADR 0014).
