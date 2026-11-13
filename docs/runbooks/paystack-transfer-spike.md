# Paystack transfer spike (T301)

**Date:** 2026-11-13
**Mode:** test (`sk_test_…`; no live calls were made)
**Purpose:** record the Paystack transfer contract, fee schedule and idempotency behaviour before
enabling the real payout adapter.

## What worked

| Step | Endpoint | Result |
| --- | --- | --- |
| Auth / balance | `GET /balance` | `200`, GHS test balance returned |
| MoMo providers | `GET /bank?currency=GHS&type=mobile_money` | `MTN`, `VOD` (Telecel), `ATL` (AirtelTigo) |
| Create recipient | `POST /transferrecipient` (`type=mobile_money`, `bank_code=MTN`, `currency=GHS`) | `201`, `recipient_code` returned; duplicate account numbers return the existing recipient |
| List transfers | `GET /transfer` | `200`, no transfers yet |

## Blocker

`POST /transfer` returns `400`:

```json
{"status": false, "message": "You cannot initiate third party payouts as a starter business"}
```

Reproduced at both GHS 10 and GHS 1 with a valid test recipient. The account is still on Paystack's
**starter business** tier; test mode inherits the restriction. Third-party payouts must be enabled
(business upgrade/compliance or Paystack support) before a live sandbox transfer can be signed off.
No transfer reached Paystack, so duplicate-reference behaviour could not be observed live.

## Fees

Paystack Ghana (support article "Transfers pricing"):

- Mobile money transfer: **GHS 1 per successful transfer** (100 pesewas)
- Bank transfer: **GHS 8 per successful transfer** (not modelled; all current destinations are MoMo)

The adapter uses `collections.payout.paystack.momo-fee-minor-units` (default `100`) and re-quotes
before every transfer through the existing claim check.

## Reference and idempotency

- Paystack reference: 16–50 characters, lowercase letters, digits, `-` and `_`
- Adapter reference: `tgthr-payout-<payout UUID>` (49 characters, unique per payout)
- A duplicate-reference `400` is resolved with `GET /transfer/verify/<reference>`:
  `success` → SUCCESS, `failed`/`reversed` → FAILED, `pending`/`otp` → UNKNOWN
- The returned `transfer_code` is persisted as the unique provider reference

## Adapter mapping

- Transfer amount is the **beneficiary net** (`amount − fee`); the goal bears the fee in the ledger
- Recipient is created from the goal's encrypted destination phone and network:
  `MTN → MTN`, `TELECEL → VOD`, `AT → ATL`
- Stored destinations are E.164 (`+233XXXXXXXXX`); the adapter converts them to the local MoMo form
  (`0XXXXXXXXX`) before calling Paystack
- A success is accepted only when the returned `amount` equals the beneficiary net and the currency
  is `GHS`; otherwise the result is UNKNOWN
- Provider statuses: `success` → SUCCESS, `failed`/`reversed` → FAILED, `otp`/`pending` → UNKNOWN
- 401/403/408/429 and 5xx/timeouts are UNKNOWN; definite 4xx rejections are FAILED
- Tests use `FakePaystackServer`; no automated test calls Paystack

## OTP prerequisite

If Paystack transfers OTP is enabled, every test transfer returns `otp`. The current adapter maps
that to UNKNOWN because the platform has no business-OTP finalization channel. **Disable transfers
OTP in the Paystack dashboard** before live payouts; otherwise payouts will stall in EXECUTING.

## Next

1. Enable transfers / upgrade the business from starter.
2. Re-run this spike: recipient → transfer → verify → duplicate reference.
3. Record the date, commit and outcomes here.
4. Only then enable `collections.payout.transfer-provider=paystack` in production.
