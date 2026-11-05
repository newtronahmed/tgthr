# Domain spec: Ledger

| Field | Value |
| --- | --- |
| Status | Draft |
| Area code | LEDGER |
| Related features | FEAT-001, FEAT-002 |

## Purpose

The ledger is the only record of money state. Everything a user sees about balances is computed from ledger entries. Constitution Article I governs this file.

## Terms

| Term | Meaning |
| --- | --- |
| Pesewa | The minor unit of the Ghana cedi. 100 pesewas = GHS 1. All amounts are integer pesewas. |
| Entry | One immutable debit or credit line with an account, an amount and a type. |
| Posting set | All entries created for one business event. A posting set nets to zero. |
| Goal pool | The account holding what has been collected for a goal and is owed to its destination. |
| Partner held | The account representing funds held for us by the licensed payment partner. |

## Entry types

| Type | Meaning | Posted when |
| --- | --- | --- |
| CONTRIBUTION | A confirmed contribution enters the goal pool | Contribution becomes CONFIRMED |
| FEE | Processing fee collected from the contributor and passed to the partner | Contribution becomes CONFIRMED, if a fee applies |
| PLATFORM_FEE | Platform service fee collected from the contributor | Contribution becomes CONFIRMED, if a platform fee applies |
| PAYOUT | Money leaves the goal pool to the destination | Payout is sent |
| PAYOUT_FEE | Cost of the disbursement | Payout is sent |
| REVERSAL | Undoes an earlier posting set | A refund or correction is approved |
| ADJUSTMENT | Operator correction, always paired with a reason | Reconciliation finds a difference |
| RESERVE_ROLLOVER | Surplus moves to the group's welfare reserve | Only for goals that opted in |

Confirmed account mapping (ADR 0006):

| Business event | Debit | Credit |
| --- | --- | --- |
| CONTRIBUTION of A plus FEE of F plus PLATFORM_FEE of P | Partner held A + F + P | Goal pool A; FEE F; PLATFORM_FEE P |
| PAYOUT of X less PAYOUT_FEE of G | Goal pool X | Partner held X − G; PAYOUT_FEE G |
| RESERVE_ROLLOVER of S | Goal pool S | Welfare reserve S |

`FEE` and `PAYOUT_FEE` are clearing accounts. They are cleared when the partner retains or deducts the fee; the settlement postings are in ADR 0006. `PLATFORM_FEE` is platform revenue, not a clearing account; see ADR 0007.

## Derived values

- Goal pool balance = sum of credits to the goal pool minus sum of debits from it.
- A goal's raised total counts CONTRIBUTION entries only, net of REVERSAL entries.
- Platform revenue is the sum of PLATFORM_FEE credits minus any reversal of them.
- No balance is stored. Views and queries compute them from entries.

## Invariants

| ID | Statement |
| --- | --- |
| INV-LEDGER-001 | Every posting set nets to zero: total debits equal total credits. |
| INV-LEDGER-002 | Entries are never updated or deleted. A correction is a new entry. |
| INV-LEDGER-003 | A goal pool balance is never negative, and a payout cannot exceed it. |
| INV-LEDGER-004 | An idempotency key appears at most once, enforced by a database unique constraint. |
| INV-LEDGER-005 | Every amount is a non-negative integer number of pesewas, and posted entries are strictly positive. |
| INV-LEDGER-006 | Balances are derived from entries on every read; no column stores a balance. |

## Settled partner model

Resolved 2026-11-05. The partner is Paystack (ADR 0006).

- Paystack deducts its transaction fee per transaction and settles the remainder, so settlement is net of fees; it does not invoice fees separately.
- The contributor bears the collection fee. The total charged is grossed up so the goal pool receives the full contribution amount and the platform receives its full service fee; the difference is a FEE entry plus a PLATFORM_FEE entry (ADR 0007).
- The platform service fee is 1.5% of the contribution amount, rounded up to the nearest pesewa, and does not reduce the goal amount.
- The destination bears payout fees. Paystack Ghana charges GHS 1 per mobile-money transfer and GHS 8 per bank transfer; the transfer amount is reduced by the fee so the goal pool is debited only the payout amount.

The confirmed posting sets, including the FEE and PAYOUT_FEE clearing entries, are in ADR 0006.
