# Payout operations runbook (T362)

**Audience:** on-call engineer/operator handling payout alerts.
**Scope:** fee mismatches, stuck execution, contributor notifications, approval SMS, public reports,
destination/phone decryption, and the Paystack transfer go-live checklist.
**Rule:** never edit ledger rows, payout state or provider references by hand. Corrections are new
ledger entries and new payout requests. Never log or paste a phone number, approval code or secret
into a ticket.

## Configuration and flags

| Variable | Default | Meaning |
| --- | --- | --- |
| `PAYOUT_ENABLED` | `false` | Public/organizer payout endpoints |
| `PAYOUT_EXECUTION_ENABLED` | `false` | Claim/execute worker and stuck sweep |
| `PAYOUT_TRANSFER_PROVIDER` | `none` | Set `paystack` for the real transfer adapter |
| `PAYOUT_FEE_PROVIDER` | `none` | `paystack` (GHS 1 MoMo), `configured` (fixed fallback) |
| `PAYOUT_PAYSTACK_MOMO_FEE_MINOR_UNITS` | `100` | Fee quoted by the Paystack fee provider |
| `PAYOUT_TRANSFER_FEE_MINOR_UNITS` | required when `fee-provider=configured` | Fallback fee |
| `PAYOUT_MINIMUM_HOLD_MINUTES` | `1` | Hold floor; must be at least 1 |
| `PAYOUT_STUCK_AFTER` | `PT10M` | EXECUTING age before the sweep flags a payout |
| `PAYOUT_PROVIDER_TIMEOUT` | `PT5S` | Validation floor only: `stuck-after` must be greater; it does **not** set the HTTP deadline |
| `PAYSTACK_BASE_URL` | `https://api.paystack.co` | Paystack API base |
| `PAYSTACK_SECRET_KEY` | required | Bearer secret; test and live keys are separate |
| `PAYSTACK_READ_TIMEOUT` | `PT5S` | Paystack HTTP read deadline |
| `PAYER_PHONE_KEY` | required | Base64 32-byte phone encryption key |

The YAML placeholders resolve from the short names above; Spring also accepts direct
`COLLECTIONS_PAYOUT_*` and `COLLECTIONS_PAYMENTS_PAYSTACK_*` overrides. During an incident, inspect
the process environment for `collections.payout.*` and `collections.payments.paystack.*`, not only
the short names.

Enabling `PAYOUT_ENABLED` also requires a `PAYOUT_FEE_PROVIDER` (`paystack` or `configured`); without
a fee bean the request path fails at startup. Enabling `PAYOUT_EXECUTION_ENABLED` requires a
`PAYOUT_TRANSFER_PROVIDER`; startup intentionally fails rather than execute with no provider.

## Alerts and metrics

| Alert | Metric | Meaning |
| --- | --- | --- |
| `PAYOUT_FEE_MISMATCH` | `payout.fee_mismatch` | Re-quote differs from the stored fee; payout is FAILED, no transfer, no ledger |
| `PAYOUT_EXECUTION_STUCK` | `payout.execution.unknown` + `PROVIDER_UNKNOWN` event | Provider result unknown/timeout (immediate) |
| `PAYOUT_EXECUTION_STUCK` | `payout.execution.reference_rejected` | Provider reference could not be persisted |
| `PAYOUT_EXECUTION_STUCK` | `payout.execution.stuck` | Completion not persisted, or the later stale-EXECUTING sweep |
| — | `payout.execution.stuck.current` | Current EXECUTING backlog older than `stuck-after` |
| `SMS_FAILURE` | `payout.approval.sms.failed`, `payout.organizer_notification.failed`, `payout.notification.failed` | Approval/organizer/contributor SMS failed |
| `PAYOUT_BLOCKED_FROZEN` | `payout.execution.frozen_blocked` | One summary per worker scan (`payout-worker`) plus one per frozen payout in `claimNext` |
| `PAYOUT_REPORTED` | `payout.report.received` | Contributor report; payout state never changes |

## 1. Paystack transfer go-live checklist

Use `docs/runbooks/paystack-transfer-spike.md` for the full spike.

1. Paystack business must allow **third-party payouts** (not a starter business).
2. **Disable transfers OTP** in the dashboard. The adapter maps `otp` to UNKNOWN because there is no
   business-OTP finalization channel.
3. Confirm the MoMo fee schedule: **GHS 1 per successful MoMo transfer** (GHS 8 bank, not modelled).
4. Set `PAYOUT_TRANSFER_PROVIDER=paystack` and `PAYOUT_FEE_PROVIDER=paystack`; keep execution
   disabled until a test-mode transfer succeeds.
5. Verify the test flow: recipient → transfer → `GET /transfer/verify/<reference>` → duplicate
   reference. Record date, commit and outcome in the runbook.
6. Automated tests never call Paystack; only this manual suite does.

Reference format: `tgthr-payout-<payout UUID>` (49 chars). Paystack accepts 16–50 lowercase
letters/digits/`-`/`_`. The adapter verifies the returned amount equals the beneficiary net and the
currency is GHS; a mismatch is UNKNOWN.

## 2. Fee mismatch

**Symptom:** `PAYOUT_FEE_MISMATCH`, `payout.fee_mismatch`, payout state `FAILED`, no provider call.

**Checks**

```sql
select id, state, amount_minor_units, fee_minor_units, net_minor_units, failure_code
from payouts where id = '<payout-id>';
select event, from_state, to_state, occurred_at
from payout_events where payout_id = '<payout-id>' order by occurred_at;
select count(*) from ledger_entries where payout_id = '<payout-id>';
```

Expect `failure_code = PAYOUT_FEE_MISMATCH`, a `FEE_MISMATCH` event, and **zero** ledger rows.

**Cause and action**

- The request-time quote differs from the execution-time quote. With `fee-provider=paystack`, check
  `PAYOUT_PAYSTACK_MOMO_FEE_MINOR_UNITS`; with `configured`, check
  `PAYOUT_TRANSFER_FEE_MINOR_UNITS` and that all instances run the same value.
- Do **not** retry the FAILED payout; it is terminal and has no ledger entry. Fix the fee config,
  redeploy consistently, and ask the organizer to request a new payout with a new idempotency key.

## 3. Stuck EXECUTING

**Symptom:** `PAYOUT_EXECUTION_STUCK`, payout state `EXECUTING`. An immediate unknown result writes a
`PROVIDER_UNKNOWN` event and increments `payout.execution.unknown`; the later sweep adds a
`VERIFICATION_REQUIRED` event and increments `payout.execution.stuck` and the backlog gauge.

```sql
select id, state, amount_minor_units, fee_minor_units, net_minor_units,
       provider_reference, failure_code, executing_at, updated_at
from payouts where id = '<payout-id>';
select event, from_state, to_state, occurred_at, detail
from payout_events where payout_id = '<payout-id>' order by occurred_at;
select count(*) from ledger_entries where payout_id = '<payout-id>';
```

**Verify with Paystack** (test key for test mode; never paste the key):

```bash
curl -s -H "Authorization: Bearer $PAYSTACK_SECRET_KEY" \
  "https://api.paystack.co/transfer/verify/tgthr-payout-<payout-id>"
```

| Paystack result | Action |
| --- | --- |
| `success`, amount = net, currency GHS | Do **not** re-send. Capture the response, transfer code and time; escalate to engineering to complete/reconcile the payout and post the one ledger set |
| `failed` / `reversed` | Do not re-send. Capture evidence; escalate for a state/ledger reconciliation decision |
| `pending` / `otp` | Wait or fix OTP; the platform has no OTP finalization. Escalate before any manual action |
| 404 / unknown | Do not blindly retry the transfer. Capture the reference and escalate; a manual provider search may be required |

Never call `POST /transfer` for this payout, and never mint a new reference. Repeating the same
`tgthr-payout-<id>` is the duplicate-reference path (not yet observed live); a **new** reference is
what would send the money again. If the provider reference is absent, the payout may have been
claimed before the provider call; treat it as manual reconciliation.

## 4. Notification failures

**Contributor notifications:** `SMS_FAILURE` with `payout.notification.failed`; the payout stays
`COMPLETED` and the failed claim row is deleted, so the contributor can be retried.

```sql
select count(*) from payout_notifications where payout_id = '<payout-id>';
select count(distinct payer_phone_hash) from contributions
where goal_id = (select goal_id from payouts where id = '<payout-id>')
  and state = 'CONFIRMED' and requires_reconciliation = false;
```

`notifyCompleted(payoutId)` is idempotent: it inserts only missing `(payout_id, payer_phone_hash)`
rows and sends once per contributor. There is **no operator HTTP endpoint** yet (FEAT-005); a retry
currently requires a controlled one-off invocation of the service after the SMS provider is fixed.
Do not send contributor SMS by hand and do not log/decrypt the payer phone outside the service.

**Approval SMS:** `SMS_FAILURE` from the approval path leaves the payout `REQUESTED`; the failed
challenge is consumed. Re-request with the same idempotency key after the provider recovers; the
request path resends. Check `payout_approvals` for consumed rows and `payout_events` for the state.

**Organizer notification:** `SMS_FAILURE` on decline/expiry/exhaustion; the payout state is already
terminal and does not change. Retry is informative only.

## 5. Public reports and frozen goals

- `PAYOUT_REPORTED`: a rate-limited contributor report is stored and alerted; the payout state never
  changes. Review the report and the payout evidence; escalate suspected fraud to the operator.
- `PAYOUT_BLOCKED_FROZEN`: READY payouts are waiting on a frozen goal. Do not force execution; the
  organizer can cancel, or the goal can be unfrozen through the operator path (FEAT-005).
  `reportFrozenBlocked` emits one summary alert with subject `payout-worker`; `claimNext` emits one
  alert per frozen payout with that payout's id. The `payout.execution.frozen_blocked` metric is
  incremented in both.

## 6. Decryption operations

Destinations, approver phones and payer phones are encrypted with `PAYER_PHONE_KEY` (32-byte base64,
AES-GCM). Plaintext must only be produced by the application at the moment of an SMS or provider
call.

- Decrypt only through `PhoneCipher` in a trusted process; never with ad-hoc SQL or online tools.
- Never print decrypted values to logs, tickets, chat or shell history.
- If a one-off decryption is unavoidable, use a secure operator session with the production key,
  output only the minimum needed to a protected channel, and record who accessed it and why.
- There is no key-rotation procedure; changing the key makes existing ciphertext undecryptable.
  Treat the key as long-lived and restrict access to the secret manager.

## 7. Evidence and escalation

Capture for every incident: payout ID, state, event list, provider reference/transfer code, Paystack
verify response (redacted), ledger count, alert/metric timestamps, and the configuration values in
effect. Never include phone numbers, codes or secrets.

**CODEOWNER review:** the ledger/payment posting path still requires the fresh review tracked as T365
before `PAYOUT_TRANSFER_PROVIDER=paystack` is enabled in production.

## Known gaps

- No operator UI/API for completion, notification retry, refunds or reconciliation (FEAT-005).
- Paystack transfers OTP cannot be finalized.
- Live payouts remain blocked until the Paystack account is upgraded from starter business.
- Settlement ingestion is not automated; unmatched provider items require manual reconciliation.
