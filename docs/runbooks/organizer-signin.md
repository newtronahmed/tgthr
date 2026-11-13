# Organizer sign-in and goal creation

## Production

- Set `WEB_ORIGIN` to the exact public web origin (scheme and host), `SMS_PROVIDER=arkesel` with
  `ARKESEL_SMS_API_KEY` and an approved `ARKESEL_SENDER_ID`, and `ACCOUNT_RESOLVER=paystack` (the
  resolver is implemented; the live spike uses the test key). See `docs/runbooks/sms-operations.md`
  for the free sandbox check and the one-real-SMS OTP test.
- Keep `COOKIE_SECURE=true` (the default). The session cookie is HttpOnly, SameSite=Lax, 30 days.
- Never set `SMS_PROVIDER=dev` or `ACCOUNT_RESOLVER=dev` in production: both bypass the real SMS
  and destination-name checks.

## Local web E2E / test only

The browser E2E starts the backend with:

```
SPRING_PROFILES_ACTIVE=e2e SMS_PROVIDER=dev ACCOUNT_RESOLVER=dev COOKIE_SECURE=false WEB_ORIGIN=http://127.0.0.1:3200
```

`GET /v1/dev/otp/last?phone=...` then returns the latest code for that phone. The dev SMS sender,
lookup endpoint and dev resolver require the `e2e` profile and the `dev` provider values; they must
never be active in a deployed environment. The `logging` sender redacts the message and does not
deliver it; it never logs the code.
