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
| `REFUND_VERIFICATION_INITIAL_DELAY` | `PT5M` | First automatic re-check delay |
| `REFUND_VERIFICATION_MAX_DELAY` | `PT6H` | Backoff ceiling |
| `REFUND_VERIFICATION_MAX_ATTEMPTS` | `8` | Automatic checks before escalation |
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
       state, provider_reference, failure_code, verification_attempts,
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
       provider_reference, verification_attempts, verification_exhausted_at
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

1. T401 records whether a sandbox refund returns the processing fee. Until then keep
   `REFUND_PARTNER_RETAINS_PROCESSING_FEE=true`; T499 gates the loss set on the provider fields.
2. Use a separate test key and test transaction; never refund a real contributor during the spike.
3. Record the request body, response, refund reference format and final status in
   `docs/runbooks/refund-operations.md`.
4. Set `REFUND_PROVIDER=paystack` and `REFUNDS_ENABLED=true` only after T401 and T494 are signed off.
5. Confirm the verification path with both documented shapes: `GET /refund?transaction=` (array) and
   `GET /refund/{reference}` (object).
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
