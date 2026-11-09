# Domain spec: Payout state machine

| Field | Value |
| --- | --- |
| Status | Accepted |
| Area code | PAYOUT |
| Related features | FEAT-001, FEAT-002, FEAT-003 |

## Purpose

A payout moves settled goal funds to the locked destination. This spec fixes states, transitions and
money rules so no path bypasses reservations, approval, the mandatory hold, a freeze or idempotency.

## Terms

| Term | Meaning |
| --- | --- |
| Requested amount X | All-in goal-pool debit, integer minor units. |
| Payout fee G | Provider transfer fee, quoted before request; goal bears it. |
| Beneficiary net | X − G. |
| Available | `min(goal pool balance − active reservations, max(0, signed settled goal amount))`; fees and reconciliation-flagged contributions excluded (ADR 0014). |
| Active reservation | A payout in `REQUESTED`, `APPROVED`, `HOLD`, `READY` or `EXECUTING`. |
| Approval rule | `OFF`, `ABOVE_THRESHOLD` or `ALWAYS`; a designated member approves by SMS OTP when triggered. |
| Hold | Mandatory positive `holdWindowMinutes` after approval. |

## States and transitions

| From | Event | To | Side effects |
| --- | --- | --- | --- |
| REQUESTED | Approval not required, or approver verified | APPROVED | Approval timestamp; no ledger |
| REQUESTED | Approver declines | CANCELLED | No ledger; alert |
| REQUESTED | Organizer cancels | CANCELLED | No ledger; reservation released |
| REQUESTED | Approval token expires | CANCELLED | No ledger; organizer notified; reservation released |
| APPROVED | Hold window starts | HOLD | Hold expiry recorded |
| APPROVED | Organizer cancels | CANCELLED | Reservation released |
| HOLD | Hold expires | READY | No ledger |
| HOLD | Organizer cancels | CANCELLED | Reservation released |
| READY | Execution worker claims while goal not FROZEN | EXECUTING | Provider reference; no ledger |
| READY | Fee re-quote differs | FAILED | `PAYOUT_FEE_MISMATCH`; no provider call; reservation released |
| READY | Organizer cancels | CANCELLED | Reservation released |
| READY | Goal FROZEN | READY | Execution blocked; alert/metric |
| EXECUTING | Provider success | COMPLETED | One ledger set and event |
| EXECUTING | Definitive provider failure | FAILED | No ledger; reservation released; terminal; no auto retry |
| EXECUTING | Timeout/unknown | EXECUTING | Verify; never blindly re-execute |

No other transitions are legal. `COMPLETED`, `FAILED` and `CANCELLED` are terminal. A `FAILED` payout
may be replaced by a new request with a new idempotency key. The execution worker uses a
compare-and-set/row lock, so cancel and execute cannot both win.

## Approval

- The approver is required when the rule triggers and must differ from the organizer.
- The challenge has a hashed link token (24 h), a hashed six-digit code (10 min), five attempts and
  a 60-second resend window. Resend invalidates the previous token and code. Approval, decline and
  resend are rate-limited per phone/IP.
- An expired token cancels the payout; a decline cancels it. A required payout cannot enter HOLD,
  READY or EXECUTING before approval.
- The approver phone is encrypted; only the name is public.

## Contributor safeguards (Article V.2)

- A payout in HOLD and after it is sent is visible on the public goal page (amount, fee, state, hold
  expiry; no phones).
- A contributor can submit a rate-limited report about a payout; reports alert the operator and do
  not change the payout automatically.
- On COMPLETED, funded contributors are notified once per payout using their stored payer phone;
  delivery failures alert and do not change the payout.

## Invariants

| ID | Statement |
| --- | --- |
| INV-PAYOUT-001 | Payout amount never exceeds `available`; unsettled, suspended and already-reserved funds are excluded. |
| INV-PAYOUT-002 | Only the transitions in the table are legal; execution is impossible before approval and the positive hold. |
| INV-PAYOUT-003 | A FROZEN goal blocks new requests and READY→EXECUTING; cancellation is impossible from EXECUTING or a terminal state. |
| INV-PAYOUT-004 | Approval tokens/codes are single-use, expiring, attempt-limited and invalidated on resend; the approver is distinct from the organizer. |
| INV-PAYOUT-005 | A payout posts exactly one ledger set, only on COMPLETED; request, hold, cancel, fee mismatch and failure post nothing; a zero fee omits the fee line. |
| INV-PAYOUT-006 | Approver and destination phones are encrypted at rest and never appear in responses or logs. |
| INV-PAYOUT-007 | Active payouts reserve their amount under the goal lock; concurrent requests cannot spend the same available balance twice. |
| INV-PAYOUT-008 | Every held/sent payout is reportable by contributors and completion notifies funded contributors once; notification failures never change the payout. |

## Open questions

None. See ADR 0016 and the spec decisions.
