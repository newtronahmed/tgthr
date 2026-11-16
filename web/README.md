# tgthr web

Next.js (App Router, TypeScript, Tailwind CSS 4) public app: the shared goal page, the guest
mobile-money checkout and the receipt. The Figma prototype that drives the visual language lives
in `design/tgthr_figma_design_prototype.html`.

## Environment

- `API_BASE_URL` — Spring Boot API base including the context path. Default
  `http://localhost:8080/v1`.
- `NEXT_PUBLIC_SITE_URL` — canonical site URL for Open Graph metadata. Default
  `http://localhost:3000`.
- `COOKIE_SECURE` — set `false` only for local HTTP builds; defaults to secure in production. The
  operator session cookie is `HttpOnly`, `SameSite=Strict` and scoped to `/operator`.

No secrets belong here. The browser never calls the backend directly; route handlers under
`app/api` proxy it, which keeps the API base server-side and avoids CORS.

## Commands

```
npm ci          # install
npm run dev     # http://localhost:3000
npm run build   # production build (also type-checks)
npm run start   # serve the production build
```

## Routes

- `/g/[slug]` — public goal: progress, payout rules, feed, checkout.
- `/c/[id]` — live status and receipt; polls while pending.
- `/signin` — organizer phone OTP sign-in.
- `/organizer`, `/organizer/goals/new`, `/organizer/goals/[id]` — dashboard, goal wizard and close.
- `/operator/signin`, `/operator/enroll/[token]` — operator password + TOTP sign-in and one-time
  enrollment.
- `/operator` — refund queue, request form and provider verification.
- `/operator/reconciliation` — suspense queue, reclassify/refund/income actions and income approval.
- `/operator/api/[...path]` — operator BFF used by enrollment; refund and reconciliation money
  actions run as server actions.
- `/api/...` — server-side proxies to the Spring API, including `/api/auth/*` and `/api/organizer/*`.

The operator console needs `OPERATOR_ENABLED=true` (with `OPERATOR_TOTP_ENCRYPTION_KEY`) and
`REFUNDS_ENABLED=true` on the backend. The first admin is provisioned from the deploy-time bootstrap
token; see `docs/runbooks/operator-access.md`.

Fees are never computed here: the checkout requests `/public/goals/{slug}/contribution-quote`
from the API and displays the returned breakdown.

## Browser E2E

The checkout and organizer tests need a running stack: Postgres, the backend, the fake Paystack and
the web server, with the E2E goal seeded. The organizer test also needs the backend started with
`SPRING_PROFILES_ACTIVE=e2e SMS_PROVIDER=dev ACCOUNT_RESOLVER=dev COOKIE_SECURE=false
WEB_ORIGIN=http://127.0.0.1:3200` so it can read the test code from `/v1/dev/otp/last`. Never use
these flags in production.

```
# terminal 1: fake provider
PAYSTACK_SECRET_KEY=test-secret WEBHOOK_URL=http://127.0.0.1:8090/v1/webhooks/momo \
  node ../tools/paystack-fake/server.mjs

# terminal 2: backend (see backend/README for env), then seed
PGPASSWORD=collections psql -h 127.0.0.1 -U collections -d collections -f e2e/seed.sql

# terminal 3: web
API_BASE_URL=http://127.0.0.1:8090/v1 npm run build
API_BASE_URL=http://127.0.0.1:8090/v1 npm run start -- --port 3200
E2E_BASE_URL=http://127.0.0.1:3200 npx playwright install chromium
E2E_BASE_URL=http://127.0.0.1:3200 npm run e2e
```

CI runs the same flow in the `Web E2E` job.
