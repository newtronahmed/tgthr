# Manual Paystack sandbox suite (T096)

Run this before the first live payment. It exercises the real sandbox end to end; automated tests
never call Paystack (Constitution III.3). Record the date, commit and outcomes in a PR comment.

## Prerequisites

- Paystack sandbox account and secret key.
- A public HTTPS tunnel to the API, for example `cloudflared tunnel --url http://localhost:8090`.
- PostgreSQL and the API running with:
  - `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`
  - `SERVER_PORT=8090`
  - `PAYSTACK_BASE_URL=https://api.paystack.co`
  - `PAYSTACK_SECRET_KEY=<sandbox secret>`
  - `PAYER_PHONE_KEY=<32-byte base64 key>`
- The web app running with `API_BASE_URL=http://localhost:8090/v1`.
- In the Paystack dashboard, set the webhook URL to `https://<tunnel-host>/v1/webhooks/momo`.
- Use the sandbox MoMo test numbers from the Paystack dashboard; they change, so do not hardcode
  them here.

Seed a goal:

```sql
insert into goals (slug, title, template, target_type, target_amount_pesewas, destination_type,
                   second_approver, hold_window_minutes, surplus_rule, status)
values ('sandbox-demo', 'Sandbox demo', 'TRIP', 'OPEN', 500000, 'BENEFICIARY_MOMO', 'OFF', 60,
        'PAY_BENEFICIARY', 'OPEN');
```

## Scenarios

| # | Scenario | Steps | Expected |
| --- | --- | --- | --- |
| 1 | Happy path | Contribute on `/g/sandbox-demo`, approve the sandbox prompt | 202 → PENDING → CONFIRMED; receipt shows fees and reference; four balanced ledger entries |
| 2 | Duplicate SUCCESS | Replay the same callback from the dashboard | 204; still one posting set; no second ledger set |
| 3 | Declined | Use the sandbox decline number | FAILED; no ledger; retry creates a new contribution |
| 4 | Abandoned / timeout | Start a prompt, do not approve; wait 5 minutes | Status job polls, marks EXPIRED, no ledger |
| 5 | Late SUCCESS | On an EXPIRED or FAILED contribution, replay a signed success | CONFIRMED once, ledger posted, `LATE_SUCCESS` alert/log |
| 6 | Invalid signature | POST a callback with a wrong `x-paystack-signature` | 401; no state change; `invalid_signature` counter |
| 7 | Amount mismatch | Replay a signed success with `amount + 1` | 204; state unchanged; `AMOUNT_MISMATCH` alert |
| 8 | Unknown reference | Replay a signed success with a random reference | 204; no state change; `UNKNOWN_REFERENCE` alert |
| 9 | Public privacy | Open the goal, contribution status and receipt | No phone number in any body |
| 10 | Ledger balance | `select posting_set_id, sum(case when direction='DEBIT' then amount_pesewas else -amount_pesewas end) from ledger_entries group by 1;` | Every set nets to zero |

## Replay helper

```bash
WEBHOOK_URL=https://<tunnel-host>/v1/webhooks/momo \
SECRET_KEY=<sandbox secret> \
REFERENCE=<contribution id> \
AMOUNT_PESEWAS=<total> \
OUTCOME=success \
./tools/paystack-sandbox/replay-callback.sh
```

## Sign-off

| Field | Value |
| --- | --- |
| Date | |
| Commit | |
| Sandbox account | |
| Scenarios passed | /10 |
| Notes | |
| Signed off by | |
