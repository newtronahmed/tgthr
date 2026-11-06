# tgthr web

Next.js (App Router, TypeScript, Tailwind CSS 4) public app: the shared goal page, the guest
mobile-money checkout and the receipt. The Figma prototype that drives the visual language lives
in `design/tgthr_figma_design_prototype.html`.

## Environment

- `API_BASE_URL` — Spring Boot API base including the context path. Default
  `http://localhost:8080/v1`.
- `NEXT_PUBLIC_SITE_URL` — canonical site URL for Open Graph metadata. Default
  `http://localhost:3000`.

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
- `/api/...` — server-side proxies to the Spring API.

Fees are never computed here: the checkout requests `/public/goals/{slug}/contribution-quote`
from the API and displays the returned breakdown.
