# Refund operations runbook (T495)

**Audience:** on-call engineer, `REFUND_OPERATOR`, `RECONCILER` and `FINANCE_APPROVER`.
**Scope:** pre-payout refunds, provider-pending refunds, suspense reconciliation, payout conflicts and
the Paystack refund go-live checklist.
**Rule:** never edit ledger rows, refund state, resolution rows or provider references by hand.
Corrections are new ledger entries and new operator actions. Never paste a payer phone number, TOTP
code, recovery code or provider secret into a ticket, chat or log.

## Configuration and flags

| Variable | Default | Meaning |
| --- | --- | --- |
| `REFUNDS_ENABLED` | `false` | Refund and reconciliation endpoints, services and the verification job |
| `REFUND_PROVIDER` | `none` | `paystack` for the real adapter; `none` disables provider calls |
| `REFUND_PARTNER_RETAINS_PROCESSING_FEE` | `true` | Whether Paystack keeps `F`; sets the `LOSS_ALLOCATION` branch until T401 is signed off |
| `REFUND_SUSPENSE_INCOME_AFTER` | `P30D` | Minimum age before a suspense item can become miscellaneous income |
| `REFUND_VERIFICATION_INITIAL_DELAY` | `PT15M` | First automatic re-check delay |
| `REFUND_VERIFICATION_MAX_DELAY` | `PT12H` | Backoff ceiling |
| `REFUND_VERIFICATION_MAX_ATTEMPTS` | `24` | Automatic checks before escalation; the window spans 9.17 days, sized to outlast Paystack's refund settlement window (ADR 0020) |
| `REFUND_VERIFICATION_BATCH_SIZE` | `20` | Refunds per scheduled scan |
| `REFUND_VERIFICATION_SCAN_MS` | `60000` | Scheduled scan interval |
| `OPERATOR_ENABLED` | `false` | Operator identity; refund endpoints are unreachable without it |

The YAML placeholders resolve from these names; Spring also accepts direct `COLLECTIONS_*`
overrides. During an incident inspect the process environment for `collections.refunds.*`, not only
the short names.

## Alerts and metrics

| Alert | Metric | Meaning |
| --- | --- | --- |
| `REFUND_PROVIDER_PENDING` | `refunds.provider_pending{reason}` | Provider outcome unknown; reservation held; alert is published once on entry |
| `REFUND_VERIFICATION_EXHAUSTED` | `refunds.verification.exhausted` | Automatic checks exhausted; reservation held; manual verify required |
| `REFUND_COMPLETION_FAILED` | `refunds.success_not_confirmed` / provider-result apply failure | Provider may have succeeded but the ledger could not be applied |
| `RECONCILIATION_REQUIRED` | `contributions.suspense` | A payment arrived for a closed/frozen goal and is parked in `SUSPENSE` |
| — | `refunds.requested`, `refunds.succeeded`, `refunds.failed`, `refunds.verified` | Refund lifecycle |
| — | `refunds.verification.attempt{result}` | Each automatic provider check |
| — | `reconciliations.reclassified`, `reconciliations.refund_requested`, `reconciliations.income_proposed`, `reconciliations.income_approved` | Suspense actions |

Pending reasons: `TIMEOUT`, `CONFLICT`, `RATE_LIMITED`, `UNKNOWN_STATUS`, `AMOUNT_MISMATCH`,
`CURRENCY_MISMATCH`.

## 1. Request a pre-payout refund

Preconditions: contribution `CONFIRMED`, no active refund, no active payout, no `COMPLETED` payout,
and the contribution is not a suspense item.

```http
POST /operator/contributions/{contributionId}/refund
Cookie: tgthr_operator_session=...
Idempotency-Key: <8-64 chars, unique per contribution>
Content-Type: application/json

{"reason": "duplicate charge"}
```

- Requires `REFUND_OPERATOR`. The same key and reason replays the original; a different reason is
  `409 IDEMPOTENCY_CONFLICT`.
- A phone number in the reason is rejected with `422 INVALID_REFUND` before any write.
- A suspense item returns `409 SUSPENSE_REQUIRES_RECONCILIATION`; use section 4.
- The response is the refund row. Provider work happens after commit.
- Do not retry a slow request with a new key until you have read the refund list; use the same key.

## 2. Provider-pending refund

**Symptom:** alert `REFUND_PROVIDER_PENDING`, refund state `PROVIDER_PENDING`, the goal amount is still
reserved.

```sql
select id, contribution_id, goal_id, source_account, amount_minor_units, total_minor_units,
       state, provider_reference, client_reference, failure_code, verification_attempts,
       next_verification_at, verification_exhausted_at
from refunds where id = '<refund-id>';
select event, from_state, to_state, detail, created_at
from refund_events where refund_id = '<refund-id>' order by id;
```

**Do not request a second refund for the same contribution.** The scheduled job re-checks due
refunds with backoff. A `REFUND_OPERATOR` can also verify manually:

```http
POST /operator/refunds/{refundId}/verify
Content-Type: application/json

{"reason": "provider verification"}
```

`provider_requested_at` records when a provider call started. If a refund is still `PENDING` with
`provider_requested_at` null (for example the process stopped after the insert), verify submits the
refund. If an attempt was recorded, verify checks the provider instead of resubmitting.

| Result | State | Action |
| --- | --- | --- |
| Success | `SUCCEEDED` | One `REVERSAL` set (plus `LOSS_ALLOCATION` when `F` is retained) posted; reservation released; contribution `REFUNDED` |
| Definitive failure | `FAILED` | No ledger; reservation released; retry with a new key if appropriate |
| Still pending | `PROVIDER_PENDING` | No duplicate alert; inspect the latest `refund_events.detail` reason |
| Exhausted | `PROVIDER_PENDING`, `verification_exhausted_at` set | Automatic checks stop; escalate to the CODEOWNER/engineering; manual verify still works |

Exhaustion intentionally keeps the reservation: an unknown outcome is not proof that Paystack did not
refund. Releasing it could pay the goal pool and the contributor. Escalate for a provider-side check
before any state change.

## 3. Provider failure and retry

**Symptom:** `refunds.failed`, refund `FAILED`, no ledger, reservation released.

Check `failure_code` and the last `refund_events` row. `PROVIDER_REJECTED` is definitive; correct the
cause (for example a missing transaction reference) before retrying. Retry with a **new**
`Idempotency-Key`; the old key replays the failed refund.

## 4. Suspense reconciliation

Queue, oldest first, no payer phones:

```http
GET /operator/reconciliation/pending
```

| Action | Endpoint | Role | Ledger |
| --- | --- | --- | --- |
| Reclassify to the goal | `POST /operator/reconciliation/{contributionId}/reclassify` | `RECONCILER` | `CONTRIBUTION` debit `SUSPENSE`, credit `GOAL_POOL`; clears the flag |
| Refund the payer | `POST /operator/reconciliation/{contributionId}/refund` | `RECONCILER` | Full `A + P + F` `REVERSAL`; debits `SUSPENSE`, not `GOAL_POOL` |
| Propose income | `POST /operator/reconciliation/{contributionId}/income` | `RECONCILER` | Nothing yet; item must be at least `REFUND_SUSPENSE_INCOME_AFTER` old |
| Approve income | `POST /operator/reconciliation/{contributionId}/income/approve` | Different `FINANCE_APPROVER` | `ADJUSTMENT` debit `SUSPENSE`, credit `PLATFORM_REVENUE` |

Every action needs `Idempotency-Key` and a reason; a phone number in the reason is rejected with `422
INVALID_RECONCILIATION`. One resolution per contribution: a second or racing action returns `409
RECONCILIATION_ALREADY_RESOLVED`. Income approval also needs a TOTP within the five-minute step-up
window (`X-Operator-TOTP` when prompted).

Income keeps `requires_reconciliation = true` so the item never enters goal totals or the public feed.
Reclassification is the only action that clears it.

## 5. Payout conflicts

| Payout state | Refund result | Action |
| --- | --- | --- |
| `REQUESTED`, `APPROVED`, `HOLD`, `READY`, `EXECUTING` | `409 PAYOUT_IN_FLIGHT` naming the payout | Wait for a terminal payout state; resolve a stuck payout with the payout runbook, never by forcing a refund |
| `COMPLETED` | `409 REFUND_AFTER_PAYOUT` for every contribution on the goal | Post-payout recovery is P2; record the request and escalate |
| `FAILED`, `CANCELLED` | Allowed | Proceed with the normal refund |

## 6. Read-only SQL

```sql
select id, contribution_id, goal_id, state, source_account, amount_minor_units, total_minor_units,
       provider_reference, client_reference, verification_attempts, verification_exhausted_at
from refunds where contribution_id = '<contribution-id>';

select entry_type, account, direction, amount_minor_units, currency_code, posting_set_id
from ledger_entries where refund_id = '<refund-id>' order by posting_set_id, account;

select contribution_id, resolution, status, proposer_id, approver_id, posting_set_id, created_at
from reconciliation_resolutions where contribution_id = '<contribution-id>';

select action, subject_type, subject_id, reason, before_state, after_state, created_at
from operator_actions where subject_id = '<contribution-id>' order by id;
```

Never `update` or `delete` `ledger_entries`, `refund_events`, `operator_actions` or
`reconciliation_resolutions`.

## 7. Paystack refund go-live checklist

1. T401 recorded in section 9: Paystack **retains** the processing fee `F`, so keep
   `REFUND_PARTNER_RETAINS_PROCESSING_FEE=true`. The `fully_deducted` / `deducted_amount` provider
   fields do **not** report the fee-return outcome; see T499 in section 9.
2. Use a separate test key and test transaction; never refund a real contributor during the spike.
3. Record the request body, response, refund reference format and final status in section 9.
4. Set `REFUND_PROVIDER=paystack` and `REFUNDS_ENABLED=true` only after T401 and T494 are signed off.
   T401 is done; **T494 (fresh CODEOWNER review of the refund posting) is still open.**
5. Confirm the verification path: `GET /refund?reference=` resolves the refund the adapter created,
   and `GET /refund/{id}` needs the **numeric** refund id. `GET /refund?transaction=` requires the
   **numeric** transaction id, not the charge reference the application stores.
6. Confirm the scheduled job is running: `refunds.verification.attempt` moves for a test-pending
   refund, and an unwatched item reaches `REFUND_VERIFICATION_EXHAUSTED` in a controlled test.

## 8. Disable and rollback

Setting `REFUNDS_ENABLED=false` rejects **new refund requests** with `409 REFUNDS_DISABLED`. Read,
verify, completion and the scheduled verification job stay available, so existing `PENDING` and
`PROVIDER_PENDING` refunds can still drain. Suspense reconciliation endpoints remain gated by the same
flag; resolve or leave open suspense items before disabling.

Safe order:

1. Disable new refunds with `REFUNDS_ENABLED=false` if the console must stop taking requests.
2. Keep `REFUND_PROVIDER=paystack` and let the verification job drain `PROVIDER_PENDING` refunds.
3. Verify or escalate any refund that reaches `verification_exhausted_at`.
4. Never switch `REFUND_PROVIDER` to `none` with pending refunds; `none` returns `PROVIDER_DISABLED`
   as a definitive failure.
5. Resolve open suspense items before disabling if the reconciliation surface must be taken down.

## 9. Paystack refund sandbox spike (T401)

**Date:** 2026-11-26
**Mode:** test (`sk_test_…`, `api.paystack.co`, `domain: "test"`). Two test-mode card
transactions were refunded. No live call was made and no real contributor was refunded.
**Purpose:** record whether a refund returns the processing fee, the refund statuses and the
reference format before enabling the real provider.

### Fee return: the partner retains `F`

GHS balance moved `2,716,564 → 2,710,000` pesewas for gross refunds of `1,764 + 4,800 = 6,564`.
Deducting the fees (`35 + 94 = 129`) would have left `2,710,082`. The partner therefore refunds the
gross amount and keeps the processing fee, which matches Paystack's "transaction charges are
non-refundable" policy. `REFUND_PARTNER_RETAINS_PROCESSING_FEE=true` is correct and the
`LOSS_ALLOCATION` branch in ADR 0018 is the live path.

`fully_deducted` and `deducted_amount` report how much of the refund is deducted from the pending
payout, **not** whether the processing fee came back: both refunds reported
`deducted_amount = amount` and `fully_deducted = 1` while the fee was retained. Gating the loss set on
those fields (T499 as written) would gate nothing and must be rewritten.

### Create

`POST /refund` with `{"transaction": "<charge reference>", "amount": <gross>, "currency": "GHS"}`
returns `200` and:

```json
{"status": true, "message": "Refund has been queued for processing",
 "data": {"id": 19349642, "domain": "test", "currency": "GHS", "amount": 1764,
          "status": "pending", "reason": "PENDING", "deducted_amount": 0,
          "fully_deducted": false, "refund_type": "Full",
          "refund_channel": "Original Payment", "retriable": false,
          "reference": "01a21956-5c50-705f-8308-4c40a51f2d84",
          "expected_at": "2026-12-07T19:30:19.856Z"}}
```

- `POST /refund` **is** available on the current starter-business tier. Refunds are not blocked the
  way `POST /transfer` is (T301).
- The refund reference is Paystack-generated (UUID v4) when the request omits one.
- A caller-supplied `reference` is honoured **verbatim**: `tgthr-refund-t401-spike-001` persisted
  unchanged. It must be 8–64 characters of letters, digits, `_` and `-`.
- The transaction moves to `reversal-pending`, a status the adapter does not previously model.
- `expected_at` was **11 days** out. Refunds are not near-instant, and both refunds were still
  `pending` with no `refunded_at` 3 minutes after creation.

### Verification lookups

| Lookup | Result |
| --- | --- |
| `GET /refund?transaction=<numeric transaction id>` | the refund, as an array |
| `GET /refund?transaction=<charge reference>` | **empty array** |
| `GET /refund?reference=<client reference>` | the refund, as an array |
| `GET /refund/<numeric refund id>` | the refund, as an object |
| `GET /refund/<uuid reference>` | `401 "ID should be a valid integer"` |

The application stores the **charge reference** string (`contributions.provider_reference`), never
the numeric transaction id. The adapter's `?transaction=<string>` fallback therefore matched nothing,
so a refund whose submit outcome was unknown could never be resolved. The adapter now sends a
deterministic client reference (`tgthr-refund-<refund id>`) and verifies through
`GET /refund?reference=`, which resolves the same refund (T500).

The reference is written to `refunds.client_reference` in the same insert that creates the refund
(V19), before the provider call, so an operator can reconcile a `PROVIDER_PENDING` row without
recomputing it:

```sql
select id, state, provider_reference, client_reference
from refunds where id = '<refund-id>';
-- provider_reference may be null; then:
-- GET /refund?reference=tgthr-refund-<refund-id>
```

`GET /refund/<id>` accepts only the **numeric** refund id. Never substitute the client reference
there; it answers `401 "ID should be a valid integer"`.

### Duplicate submit is `400 transaction_reversed`, not `409`

```json
{"status": false, "code": "transaction_reversed", "type": "api_error",
 "message": "A refund already exist for this transaction",
 "data": {"existing_refunds": [{"id": 19349642, "reference": "01a2…", "status": "processing"}]}}
```

The adapter treated this as `PROVIDER_REJECTED`, which released the reservation and marked the refund
`FAILED` while Paystack was in fact refunding the contributor. It is now `PENDING`/`CONFLICT`, and the
existing refund's id is carried forward so verification can use it directly.

### Sign-off

| Field | Value |
| --- | --- |
| Fee returned | **No** — partner retains `F` |
| Provider available in test mode | Yes (`POST /refund` not tier-blocked) |
| Refund statuses observed | `pending`, `processing`; `reversal-pending` on the transaction |
| Reference format | Paystack UUID, or a caller-supplied 8–64 character reference |
| Verification shapes | `?reference=` and `/{numeric id}`; `?transaction=` needs the numeric id |
| Date | 2026-11-26 |
| Spike performed by | Agent session, reviewed by the CODEOWNER under T494 |
