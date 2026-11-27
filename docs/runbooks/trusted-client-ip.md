# Trusted client IP contract (T198)

**Scope:** every BFF route that forwards a caller address to the backend for per-IP rate limiting.

## Why this exists

`server.forward-headers-strategy: framework` (backend `application.yml`) makes the backend honour
`X-Forwarded-For` when it calls `servletRequest.getRemoteAddr()`. `RateLimiter.checkIp` and the auth
endpoints key their buckets on that value.

That makes the **BFF the trust boundary**. If it forwards whatever the browser sent, a caller mints a
new rate-limit bucket per request:

```http
POST /api/goals/x/quote
X-Forwarded-For: 10.0.0.1   # attacker-chosen, one per request
```

Before this fix `web/lib/client-ip.ts` preferred exactly that header over `x-real-ip`.

## The contract

1. **The BFF reads only `x-real-ip`**, the header set by the platform edge (or the reverse proxy in
   front of the web app). A browser cannot set it through the edge.
2. **A browser-supplied `x-forwarded-for` is ignored entirely.** It is never preferred, never
   appended and never used as a fallback.
3. **No trustworthy address means no address.** `clientIp` returns `undefined` and the route omits
   the header rather than sending an attacker-controlled value. The backend then limits on the BFF's
   own address, which is the safe degradation: one shared bucket instead of forgeable ones.
4. Only the BFF's `x-real-ip` is forwarded, as `x-forwarded-for`, to the backend.

## Deployment requirement

`x-real-ip` must be set by the edge and **overwritten**, not passed through, for requests to the web
app:

```nginx
proxy_set_header x-real-ip $remote_addr;
proxy_set_header x-forwarded-for "";
```

Terminating TLS at the edge is already required by HSTS, so this is the same hop.

**If the platform does not set `x-real-ip`, per-IP limiting degrades to a single shared bucket for all
callers through that BFF.** It does not become forgeable again, but it stops being per-caller. Verify
the header in staging before relying on per-IP limits in production:

```sh
curl -s -D - https://<web-host>/api/goals/example/quote -o /dev/null \
  -H 'x-forwarded-for: 1.2.3.4'   # must not change the bucket
```

## Covered by

- `web/lib/client-ip.test.ts` — a forged `x-forwarded-for` is not preferred and is not a fallback.
- `backend/.../contribution/TrustedClientIpIT` — proves the backend keys per forwarded address and
  that two callers do not share a bucket.

## Not covered

- Organizer and operator routes proxy cookies and corpora but do not forward an address; they are not
  rate limited per IP at the BFF.
- The edge itself is not in this repository. Requirement 3 above is the mitigation if it is
  misconfigured.
