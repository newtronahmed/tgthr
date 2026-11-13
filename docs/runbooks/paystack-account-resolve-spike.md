# Paystack MoMo account resolution spike (T001)

**Date:** 2026-11-13
**Mode:** test (`sk_test_…`; no live calls)
**Purpose:** record the Paystack account-resolution contract before enabling the production resolver.

## Request

```http
GET https://api.paystack.co/bank/resolve?account_number=0551234987&bank_code=MTN
Authorization: Bearer <test secret>
```

`account_number` is the **local** Ghanaian MoMo form (`0XXXXXXXXX`), not E.164. The adapter converts
stored `+233XXXXXXXXX` before calling. `bank_code` uses the Paystack transfer codes:

| Network | Paystack code |
| --- | --- |
| MTN | `MTN` |
| Telecel | `VOD` |
| AirtelTigo | `ATL` |

## Response

```json
{
  "status": true,
  "message": "Account number resolved",
  "data": {
    "account_number": "055****",
    "account_name": "<resolved name>",
    "bank_code": "MTN"
  }
}
```

`account_name` is the registered account/business name; it is the value used for the organizer's
`ADMIN_MOMO` name match. No phone number is returned beyond the submitted account number.

## Failure mapping

- Definite 4xx (not found/invalid, e.g. 404): the resolver returns `null` → goal creation returns
  422 `DESTINATION_UNVERIFIED`.
- 401/403/408/429: partner auth/timeout/throttle → `503 DESTINATION_CHECK_UNAVAILABLE`; the
  organizer can retry. These must not be reported as an unknown number.
- 5xx/timeout/transport error: `503 DESTINATION_CHECK_UNAVAILABLE`.
- Automated tests use `FakePaystackServer`; only this manual spike calls Paystack.

## Adapter

`PaystackAccountResolver` is selected with `ACCOUNT_RESOLVER=paystack` (default `none` refuses to
guess a name). It uses the configured `RestClient` and the same `PAYSTACK_*` settings. The spike was
successful for the documented test MoMo number; production rollout still needs the SMS and CODEOWNER
prerequisites in the organizer runbook.
