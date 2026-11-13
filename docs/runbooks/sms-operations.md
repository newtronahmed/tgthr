# SMS operations (Arkesel)

**Provider:** Arkesel (ADR 0009's recommended default).
**Scope:** organizer OTP delivery, approval SMS, organizer notifications and contributor payout
notifications. OTP generation and verification stay in the application; Arkesel only delivers the
message.

## Configuration

| Variable | Required | Meaning |
| --- | --- | --- |
| `SMS_PROVIDER` | yes | `arkesel` for production; `none`/`logging` locally; `dev` only with the `e2e` profile |
| `ARKESEL_SMS_API_KEY` | yes | Main API key from Dashboard → SMS API |
| `ARKESEL_SENDER_ID` | yes | Approved alphanumeric sender ID, **1–11 characters** (e.g. `Tgthr`) |
| `ARKESEL_SANDBOX` | no | `true` uses Arkesel's free sandbox (no delivery, no charge); default `false` |
| `ARKESEL_BASE_URL` | no | defaults to `https://sms.arkesel.com` |
| `ARKESEL_CONNECT_TIMEOUT` / `ARKESEL_READ_TIMEOUT` | no | defaults `PT3S` / `PT5S` |

Startup fails if `SMS_PROVIDER=arkesel` without a key or a sender ID. The key and sender ID are
secrets/config; never commit them.

## Current status (2026-11-13)

The key authenticates and the free sandbox send with sender `Tgthr` returned `200
{"status":"success", ...}`. A single **real** send to a maintainer-provided number was also accepted
by Arkesel (`200`, SMS id returned) but **did not arrive**; the Arkesel dashboard shows the `Tgthr`
sender ID as **pending approval**. No further send was attempted and no additional SMS was spent.

Do not retry until `Tgthr` is approved and active for production. Then perform the one-real-OTP test
below and record the result. Set `ARKESEL_SANDBOX=true` for local checks to avoid spending credit.

## Free sandbox check

Arkesel's `sandbox: true` accepts the request, does **not** bill and does **not** deliver to the
network. Use it to validate the key, sender ID and payload:

```bash
curl -s -X POST "$ARKESEL_BASE_URL/api/v2/sms/send" \
  -H "api-key: $ARKESEL_SMS_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"sender":"Tgthr","message":"sandbox smoke test","recipients":["233551234987"],"sandbox":true}'
```

Expected: `{"status":"success", ...}`. The sandbox message appears only in the Arkesel SMS history.

## One real OTP smoke test

Only after the sandbox passes and the sender ID is approved:

1. Set `SMS_PROVIDER=arkesel`, `ARKESEL_SENDER_ID=<approved>`, `ARKESEL_SANDBOX=false`.
2. Deploy to a staging instance and request **one** OTP to your own number.
3. Confirm the code arrives, sign in, and record the date/provider in the PR or runbook.
4. Stop. Do not loop the test; each real message consumes credit (the mNotify 10-free allowance does
   not apply to Arkesel).

Our automated suite never calls Arkesel; it uses `FakeArkeselServer`.

## Failure behaviour

`ArkeselSmsSender` maps every non-success response and transport failure to
`503 SMS_UNAVAILABLE`:

- 401/403: bad/inactive key or inactive gateway → fix credentials/config
- 402: insufficient balance/coverage → top up; pause sends and alert
- 422: invalid payload/number → fix the request; do not retry blindly
- 500/timeout: transient → the caller's flow returns 503, the challenge is cleaned up, and the user
  can retry

The approval path consumes a failed challenge, leaves the payout `REQUESTED`, and a retry with the
same idempotency key can resend. Contributor notification failures delete the claim row so a later
retry sends once.

## Security and operations

- Store the key in the secret manager; rotate it there and redeploy. Never log the key, phone number,
  message body or OTP code.
- Sender IDs must be approved in Arkesel and are limited to 11 characters; `GroupCollections` is too
  long. Use a short brand, e.g. `Tgthr`.
- Arkesel delivery callbacks are not implemented; delivery reports are visible in the dashboard.
  OTP flows never wait for a callback.
- Keep OTP rate limits and spend alerts enabled; SMS pumping is the main abuse/cost risk.
- For organizer sign-in operations, see `docs/runbooks/organizer-signin.md`.
