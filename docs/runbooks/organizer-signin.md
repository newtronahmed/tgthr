# Organizer sign-in and goal creation

## Production

- Set `WEB_ORIGIN` to the exact public web origin (scheme and host), `SMS_PROVIDER` to the
  production adapter once ADR 0009's vendor is live, and `ACCOUNT_RESOLVER` to the Paystack
  resolver once T001/T171 lands.
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
never be active in a deployed environment. The `logging` sender also writes codes to logs and is
local-only.
