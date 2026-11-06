# Domain spec: Contribution state machine

| Field | Value |
| --- | --- |
| Status | Draft |
| Area code | STATE |
| Related features | FEAT-001 |

## Purpose

A contribution is one payment attempt. Its state decides whether it counts toward a goal. Only the transitions below are legal (Constitution Article IV.2).

## States

| State | Meaning | Terminal |
| --- | --- | --- |
| INITIATED | Created; provider prompt not yet confirmed as sent | No |
| PENDING | Prompt sent; waiting for the guest and the partner | No |
| CONFIRMED | Partner reported success; ledger entries posted | No (can be reversed) |
| FAILED | Partner declined, or the prompt request failed; a late SUCCESS can still confirm | No |
| EXPIRED | No result within the timeout and the partner confirms no payment; a late SUCCESS can still confirm | No |
| REFUNDED | A confirmed contribution was reversed | Yes |

## Transitions

| From | Event | To | Side effects |
| --- | --- | --- | --- |
| INITIATED | Prompt requested successfully | PENDING | History row |
| INITIATED | Valid SUCCESS callback or poll result (arrived before PENDING was recorded) | CONFIRMED | Ledger entries posted in the same transaction; history row |
| INITIATED | Timeout and the partner confirms no payment | EXPIRED | History row |
| INITIATED | Partner rejects the prompt request | FAILED | History row |
| PENDING | Valid SUCCESS callback or poll result | CONFIRMED | Ledger entries posted in the same transaction |
| PENDING | Valid FAILED callback or poll result | FAILED | History row |
| PENDING | Timeout and the partner confirms no payment | EXPIRED | History row |
| EXPIRED | Valid late SUCCESS callback or poll result | CONFIRMED | Ledger entries posted in the same transaction; alert raised |
| FAILED | Valid late SUCCESS callback or poll result | CONFIRMED | Ledger entries posted in the same transaction; alert raised |
| CONFIRMED | Approved refund or correction | REFUNDED | REVERSAL entries |

Any other event for a given state is an illegal transition: it is logged with the contribution id and ignored.

## Invariants

| ID | Statement |
| --- | --- |
| INV-STATE-001 | A contribution only moves along the transitions in this file; every other event leaves its state unchanged. |
| INV-STATE-002 | Ledger entries exist for a contribution if and only if it is, or was, CONFIRMED. |
| INV-STATE-003 | Every state change appends a history row; history rows are never updated or deleted. |

## Resolved decisions

- 2026-11-05: a valid late SUCCESS after EXPIRED or FAILED moves the contribution to CONFIRMED and raises an alert. The two transitions above are added because Constitution I.5 forbids dropping a confirmed payment.
- 2026-11-06: a valid SUCCESS while INITIATED confirms the contribution. The callback is proof that the prompt was sent and paid; acknowledging it and leaving the row INITIATED would drop a confirmed payment. The create path's later PROMPT_SENT is then illegal and ignored.
- 2026-11-06: a stale INITIATED row whose poll confirms no payment expires. Without this, a crash between the insert and PROMPT_SENT leaves the row INITIATED forever.
